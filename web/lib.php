<?php
// Shared helpers: HTTP, TKCore moderation and the YouTube internal (Innertube) client.
// Hide-only mode: no OAuth, no server token, no delete operations.

function tkp_config() {
    static $config = null;
    if ($config === null) {
        $config = require __DIR__ . '/config.php';
    }
    return $config;
}

function tkp_session_start() {
    if (session_status() === PHP_SESSION_NONE) {
        session_name('tkp_morphe');
        session_set_cookie_params(array(
            'lifetime' => 0,
            'path'     => '/',
            'httponly' => true,
            'samesite' => 'Lax',
            'secure'   => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
        ));
        session_start();
    }
}

function tkp_json($data, $code = 200) {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store, max-age=0');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function tkp_http($url, $method = 'GET', $headers = array(), $body = null) {
    $ch = curl_init($url);
    curl_setopt_array($ch, array(
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS      => 5,
        CURLOPT_TIMEOUT        => 30,
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_CUSTOMREQUEST  => $method,
        CURLOPT_HTTPHEADER     => $headers,
        CURLOPT_USERAGENT      => 'MorpheTKProtect/2.0',
    ));
    if ($body !== null) {
        curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
    }
    $raw  = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err  = curl_error($ch);
    curl_close($ch);

    $decoded = json_decode((string) $raw, true);
    return array(
        'code'  => $code,
        'raw'   => (string) $raw,
        'json'  => is_array($decoded) ? $decoded : null,
        'error' => $err,
    );
}

function tkp_csrf_token() {
    if (empty($_SESSION['tkp_csrf'])) {
        $_SESSION['tkp_csrf'] = bin2hex(random_bytes(24));
    }
    return $_SESSION['tkp_csrf'];
}

function tkp_request_csrf() {
    $sent = '';
    if (isset($_SERVER['HTTP_X_TKP_CSRF'])) {
        $sent = (string) $_SERVER['HTTP_X_TKP_CSRF'];
    } elseif (isset($_POST['csrf'])) {
        $sent = (string) $_POST['csrf'];
    }
    if ($sent === '' || empty($_SESSION['tkp_csrf']) || !hash_equals($_SESSION['tkp_csrf'], $sent)) {
        tkp_json(array('error' => 'invalid_csrf'), 403);
    }
}

// ---------------------------------------------------------------------------
// TKCore moderation gateway (same call shape as superbotmoderator.php)
// ---------------------------------------------------------------------------

function tkp_verdict_label($type) {
    $labels = array(
        'allowed'    => 'Нормальное сообщение',
        'whitelist'  => 'Ссылка в белом списке',
        'scanner'    => 'Обнаружена ссылка',
        'obscene'    => 'Нецензурная лексика',
        'phishing'   => 'Фишинговая ссылка',
        'caps_flood' => 'Капс / флуд',
        'scam'       => 'Скам',
        'blacklist'  => 'Ссылка в чёрном списке',
        'pii'        => 'Персональные данные',
        'error'      => 'Ошибка проверки',
        'pending'    => 'Ещё не проверено',
    );
    return isset($labels[$type]) ? $labels[$type] : 'Неизвестный ответ: ' . $type;
}

function tkp_normalize_verdict_type($type) {
    if (is_string($type)) {
        $map = array(
            '-1' => 'error', '0' => 'allowed', '1' => 'obscene',
            '2' => 'phishing', '3' => 'caps_flood', '4' => 'pii', '5' => 'scam',
        );
        return isset($map[$type]) ? $map[$type] : $type;
    }
    $map = array(
        -1 => 'error', 0 => 'allowed', 1 => 'obscene',
        2 => 'phishing', 3 => 'caps_flood', 4 => 'pii', 5 => 'scam',
    );
    $key = (int) $type;
    return isset($map[$key]) ? $map[$key] : 'unknown';
}

function tkp_is_bad_verdict($type) {
    // 'error' is not a violation: moderation must fail open, not hide content.
    $safe = array('allowed', 'whitelist', 'scanner', 'error', 'unknown', 'pending');
    return !in_array($type, $safe, true);
}

function tkp_check_text($text) {
    $config = tkp_config();
    $text   = trim((string) $text);
    if ($text === '') {
        return array('type' => 'allowed', 'reason' => null, 'bad' => false, 'label' => tkp_verdict_label('allowed'));
    }

    if (!isset($_SESSION['tkp_verdicts'])) {
        $_SESSION['tkp_verdicts'] = array();
    }
    $key = md5($text);
    if (isset($_SESSION['tkp_verdicts'][$key])
        && $_SESSION['tkp_verdicts'][$key]['expires'] > time()) {
        return $_SESSION['tkp_verdicts'][$key]['value'];
    }

    $url = rtrim((string) $config['tk_api_base'], '?&') . '?'
        . http_build_query(array(
            'api_key' => (string) $config['tk_api_key'],
            'type'    => 'botmoderator',
            'phrase'  => $text,
            'phrase2' => (string) $config['tk_mode'],
            'version' => (string) $config['tk_version'],
        ));

    $response = tkp_http($url);
    $payload  = $response['json'];

    if ($response['code'] < 200 || $response['code'] >= 400 || !is_array($payload)) {
        $verdict = array(
            'type'   => 'error',
            'reason' => 'HTTP ' . $response['code'] . ($response['error'] !== '' ? ' ' . $response['error'] : ''),
            'bad'    => false,
            'label'  => tkp_verdict_label('error'),
        );
    } else {
        $rawType = isset($payload['type']) ? $payload['type'] : (isset($payload['error']) ? 'error' : 'unknown');
        $type    = tkp_normalize_verdict_type($rawType);
        $reason  = isset($payload['reason']) ? $payload['reason']
            : (isset($payload['error']) ? $payload['error'] : null);
        $verdict = array(
            'type'   => $type,
            'reason' => $reason === null ? null : (string) $reason,
            'bad'    => tkp_is_bad_verdict($type),
            'label'  => tkp_verdict_label($type),
        );
    }

    if (count($_SESSION['tkp_verdicts']) > 200) {
        $_SESSION['tkp_verdicts'] = array_slice($_SESSION['tkp_verdicts'], -100, 100, true);
    }
    $_SESSION['tkp_verdicts'][$key] = array(
        'expires' => time() + (int) $config['verdict_ttl'],
        'value'   => $verdict,
    );
    return $verdict;
}

// ---------------------------------------------------------------------------
// YouTube internal (Innertube) client - public endpoints, no registration
// ---------------------------------------------------------------------------

function tkp_innertube($endpoint, $extra = array()) {
    $config = tkp_config();
    $query = http_build_query(array(
        'key'         => (string) $config['yt_innertube_key'],
        'prettyPrint' => 'false',
    ));
    $client = array(
        'clientName'    => (string) $config['yt_client_name'],
        'clientVersion' => (string) $config['yt_client_version'],
        'hl'            => (string) $config['yt_language'],
        'gl'            => strtoupper((string) $config['yt_language']),
    );
    $body = array_merge(array('context' => array('client' => $client)), $extra);
    return tkp_http(
        'https://www.youtube.com/youtubei/v1/' . ltrim($endpoint, '/') . '?' . $query,
        'POST',
        array('Content-Type: application/json', 'Accept: application/json'),
        json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
    );
}

// Depth-first collection of every value stored under $key.
function tkp_walk_all($node, $key) {
    $found = array();
    $stack = array($node);
    while ($stack) {
        $cur = array_pop($stack);
        if (!is_array($cur)) {
            continue;
        }
        foreach ($cur as $k => $v) {
            if ($k === $key) {
                $found[] = $v;
            }
            if (is_array($v)) {
                $stack[] = $v;
            }
        }
    }
    return $found;
}

function tkp_text($value) {
    if (is_string($value) || is_int($value)) {
        return (string) $value;
    }
    if (!is_array($value)) {
        return '';
    }
    if (isset($value['simpleText'])) {
        return (string) $value['simpleText'];
    }
    if (isset($value['runs']) && is_array($value['runs'])) {
        $out = '';
        foreach ($value['runs'] as $run) {
            if (is_array($run) && isset($run['text'])) {
                $out .= (string) $run['text'];
            }
        }
        return $out;
    }
    if (isset($value['content'])) {
        return tkp_text($value['content']);
    }
    return '';
}

function tkp_video_info($videoId) {
    $videoId = (string) $videoId;
    if (!preg_match('/^[A-Za-z0-9_-]{11}$/', $videoId)) {
        return null;
    }
    if (!isset($_SESSION['tkp_videos'])) {
        $_SESSION['tkp_videos'] = array();
    }
    if (isset($_SESSION['tkp_videos'][$videoId])
        && $_SESSION['tkp_videos'][$videoId]['expires'] > time()) {
        return $_SESSION['tkp_videos'][$videoId]['value'];
    }

    $response = tkp_innertube('player', array('videoId' => $videoId));
    $details  = null;
    if (is_array($response['json'])) {
        $found = tkp_walk_all($response['json'], 'videoDetails');
        if ($found) {
            $details = $found[0];
        }
    }
    $value = null;
    if (is_array($details)) {
        $value = array(
            'id'        => (string) ($details['videoId'] ?? $videoId),
            'title'     => tkp_text($details['title'] ?? ''),
            'channelId' => (string) ($details['channelId'] ?? ''),
        );
    }
    $_SESSION['tkp_videos'][$videoId] = array(
        'expires' => time() + (int) tkp_config()['video_ttl'],
        'value'   => $value,
    );
    return $value;
}

function tkp_chat_continuation($node) {
    $pool   = $node;
    $render = tkp_walk_all($node, 'liveChatRenderer');
    $live   = tkp_walk_all($node, 'liveChatContinuation');
    if ($render) {
        $pool = $render[0];
    } elseif ($live) {
        $pool = $live[0];
    }
    foreach (array('invalidationContinuationData', 'timedContinuationData') as $key) {
        foreach (tkp_walk_all($pool, $key) as $value) {
            if (is_array($value) && !empty($value['continuation'])) {
                return (string) $value['continuation'];
            }
        }
    }
    return '';
}

function tkp_parse_chat_item($value) {
    if (!is_array($value)) {
        return null;
    }
    $text = tkp_text($value['message'] ?? null);
    if ($text === '' && isset($value['header'])) {
        $text = tkp_text($value['header']);
    }
    if ($text === '') {
        $found = tkp_walk_all($value, 'contentText');
        if ($found) {
            $text = tkp_text($found[0]);
        }
    }
    $id      = (string) ($value['id'] ?? '');
    $author  = tkp_text($value['authorName'] ?? null);
    $chan    = (string) ($value['authorExternalChannelId'] ?? '');
    $usec    = (string) ($value['timestampUsec'] ?? '');
    $pub     = '';
    if ($usec !== '') {
        $sec = (int) substr($usec, 0, 10);
        if ($sec > 0) {
            $pub = gmdate('c', $sec);
        }
    }
    if ($text === '' && $id === '' && $author === '') {
        return null;
    }
    return array(
        'id'          => $id,
        'text'        => $text,
        'author'      => $author,
        'channelId'   => $chan,
        'isOwner'     => false,
        'isModerator' => false,
        'publishedAt' => $pub,
    );
}

function tkp_extract_chat($node) {
    $out   = array();
    $stack = array($node);
    while ($stack) {
        $cur = array_pop($stack);
        if (!is_array($cur)) {
            continue;
        }
        foreach ($cur as $k => $v) {
            if (is_string($k) && preg_match('/^liveChat(Text|Paid)MessageRenderer$/', $k) && is_array($v)) {
                $item = tkp_parse_chat_item($v);
                if ($item !== null) {
                    $out[] = $item;
                }
            }
            if (is_array($v)) {
                $stack[] = $v;
            }
        }
    }
    $seen = array();
    $uniq = array();
    foreach ($out as $item) {
        $key = $item['id'] !== '' ? $item['id'] : md5($item['author'] . '|' . $item['text']);
        if (!isset($seen[$key])) {
            $seen[$key] = true;
            $uniq[] = $item;
        }
    }
    usort($uniq, function ($a, $b) {
        return strcmp($a['publishedAt'], $b['publishedAt']);
    });
    return $uniq;
}

function tkp_chat_page($videoId, $continuation = '') {
    if ($continuation === '') {
        $response = tkp_innertube('next', array('videoId' => (string) $videoId));
        $root = $response['json'];
        $next = tkp_chat_continuation($root);
        if ($next === '') {
            return array('messages' => array(), 'continuation' => '', 'error' => 'no_active_live_chat');
        }
    } else {
        $response = tkp_innertube('live_chat/get_live_chat', array('continuation' => (string) $continuation));
        $root = $response['json'];
        $next = tkp_chat_continuation($root);
        if ($next === '') {
            $next = (string) $continuation;
        }
    }
    if ($response['code'] !== 200 || !is_array($root)) {
        return array(
            'messages'     => array(),
            'continuation' => '',
            'error'        => 'YouTube API error (HTTP ' . $response['code'] . ')',
        );
    }
    return array(
        'messages'     => tkp_extract_chat($root),
        'continuation' => $next,
        'error'        => '',
    );
}

function tkp_extract_comments($node) {
    $out = array();
    foreach (tkp_walk_all($node, 'commentRenderer') as $value) {
        if (!is_array($value)) {
            continue;
        }
        $id     = (string) ($value['commentId'] ?? '');
        $text   = tkp_text($value['contentText'] ?? null);
        $author = tkp_text($value['authorText'] ?? null);
        $chan   = (string) ($value['authorEndpoint']['browseEndpoint']['browseId'] ?? '');
        if ($id === '' && $text === '') {
            continue;
        }
        $out[] = array(
            'id'          => $id,
            'text'        => $text,
            'author'      => $author,
            'channelId'   => $chan,
            'publishedAt' => tkp_text($value['publishedTimeText'] ?? null),
            'likeCount'   => tkp_text($value['voteCount'] ?? null),
        );
    }
    $seen = array();
    $uniq = array();
    foreach ($out as $item) {
        if (!isset($seen[$item['id']])) {
            $seen[$item['id']] = true;
            $uniq[] = $item;
        }
    }
    return $uniq;
}

function tkp_comments_continuation($node) {
    $pool = $node;
    $sections = tkp_walk_all($node, 'commentSectionRenderer');
    $append   = tkp_walk_all($node, 'appendContinuationItemsAction');
    if ($append) {
        $pool = $append[0];
    } elseif ($sections) {
        $pool = $sections[0];
    }
    $tokens = array();
    foreach (tkp_walk_all($pool, 'continuationItemRenderer') as $value) {
        if (!is_array($value)) {
            continue;
        }
        $token = (string) ($value['continuationEndpoint']['continuationCommand']['token'] ?? '');
        if ($token !== '') {
            $tokens[] = $token;
        }
    }
    return $tokens ? end($tokens) : '';
}

function tkp_comments_page($videoId, $continuation = '') {
    $extra = $continuation === ''
        ? array('videoId' => (string) $videoId)
        : array('continuation' => (string) $continuation);
    $response = tkp_innertube('next', $extra);
    if ($response['code'] !== 200 || !is_array($response['json'])) {
        return array(
            'comments'     => array(),
            'continuation' => '',
            'error'        => 'YouTube API error (HTTP ' . $response['code'] . ')',
        );
    }
    return array(
        'comments'     => tkp_extract_comments($response['json']),
        'continuation' => tkp_comments_continuation($response['json']),
        'error'        => '',
    );
}

function tkp_sanitize_text($text, $limit = 500) {
    $text = (string) $text;
    $text = preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/u', '', $text);
    return function_exists('mb_substr') ? mb_substr($text, 0, $limit, 'UTF-8') : substr($text, 0, $limit);
}
