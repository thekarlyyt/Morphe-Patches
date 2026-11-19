<?php
// JSON API for the Morphe YouTube / TKProtect module (hide-only mode).
require __DIR__ . '/lib.php';
tkp_session_start();

$action = isset($_GET['action']) ? (string) $_GET['action']
    : (isset($_POST['action']) ? (string) $_POST['action'] : '');

$config = tkp_config();

switch ($action) {
    case 'bootstrap':
        tkp_json(array(
            'csrf'             => tkp_csrf_token(),
            'moderationReady'  => strpos((string) $config['tk_api_key'], 'YOUR_') !== 0,
            'checkLimit'       => (int) $config['check_limit_per_poll'],
            'pollingInterval'  => 5000,
        ));
        break;

    case 'check':
        tkp_request_csrf();
        $text = isset($_POST['text']) ? (string) $_POST['text'] : '';
        tkp_json(tkp_check_text(tkp_sanitize_text($text)));
        break;

    case 'chat':
        $videoId = isset($_GET['videoId']) ? (string) $_GET['videoId'] : '';
        if (!preg_match('/^[A-Za-z0-9_-]{11}$/', $videoId)) {
            tkp_json(array('error' => 'invalid_video_id'), 400);
        }
        if (!isset($_SESSION['tkp_chat_cont'])) {
            $_SESSION['tkp_chat_cont'] = array();
        }
        $reset = !empty($_GET['reset']);
        if ($reset) {
            unset($_SESSION['tkp_chat_cont'][$videoId]);
        }
        $continuation = isset($_SESSION['tkp_chat_cont'][$videoId]) ? $_SESSION['tkp_chat_cont'][$videoId] : '';
        $page = tkp_chat_page($videoId, $continuation);
        if (!empty($page['error']) && $continuation === '') {
            tkp_json(array('error' => $page['error'], 'video' => tkp_video_info($videoId)));
        }
        if ($page['continuation'] !== '') {
            $_SESSION['tkp_chat_cont'][$videoId] = $page['continuation'];
        }

        $messages = array();
        $checked  = 0;
        foreach ($page['messages'] as $item) {
            $text = tkp_sanitize_text($item['text']);
            if ($checked < (int) $config['check_limit_per_poll']) {
                $verdict = tkp_check_text($text);
                $checked++;
            } else {
                $key = md5($text);
                $verdict = isset($_SESSION['tkp_verdicts'][$key])
                    ? $_SESSION['tkp_verdicts'][$key]['value']
                    : array('type' => 'pending', 'reason' => null, 'bad' => false, 'label' => tkp_verdict_label('pending'));
            }
            $item['text']     = $text;
            $item['verdict']  = $verdict;
            $item['hidden']   = !empty($verdict['bad']);
            $item['canDelete'] = false;
            $messages[] = $item;
        }

        tkp_json(array(
            'video'             => tkp_video_info($videoId),
            'messages'          => $messages,
            'error'             => $page['error'],
            'pollingInterval'   => 5000,
            'moderationReady'   => strpos((string) $config['tk_api_key'], 'YOUR_') !== 0,
        ));
        break;

    case 'comments':
        $videoId = isset($_GET['videoId']) ? (string) $_GET['videoId'] : '';
        if (!preg_match('/^[A-Za-z0-9_-]{11}$/', $videoId)) {
            tkp_json(array('error' => 'invalid_video_id'), 400);
        }
        if (!isset($_SESSION['tkp_comments_cont'])) {
            $_SESSION['tkp_comments_cont'] = array();
        }
        $reset = !empty($_GET['reset']);
        if ($reset) {
            unset($_SESSION['tkp_comments_cont'][$videoId]);
        }
        $continuation = isset($_SESSION['tkp_comments_cont'][$videoId]) ? $_SESSION['tkp_comments_cont'][$videoId] : '';
        $page = tkp_comments_page($videoId, $continuation);
        if (!empty($page['error']) && $continuation === '') {
            tkp_json(array('error' => $page['error']));
        }
        if ($page['continuation'] !== '') {
            $_SESSION['tkp_comments_cont'][$videoId] = $page['continuation'];
        }

        $comments = array();
        $checked  = 0;
        foreach ($page['comments'] as $item) {
            $text = tkp_sanitize_text($item['text']);
            if ($checked < (int) $config['check_limit_per_poll']) {
                $verdict = tkp_check_text($text);
                $checked++;
            } else {
                $key = md5($text);
                $verdict = isset($_SESSION['tkp_verdicts'][$key])
                    ? $_SESSION['tkp_verdicts'][$key]['value']
                    : array('type' => 'pending', 'reason' => null, 'bad' => false, 'label' => tkp_verdict_label('pending'));
            }
            $item['text']      = $text;
            $item['verdict']   = $verdict;
            $item['hidden']    = !empty($verdict['bad']);
            $item['canDelete'] = false;
            $comments[] = $item;
        }

        tkp_json(array(
            'video'           => tkp_video_info($videoId),
            'comments'        => $comments,
            'error'           => $page['error'],
            'moderationReady' => strpos((string) $config['tk_api_key'], 'YOUR_') !== 0,
        ));
        break;

    default:
        tkp_json(array('error' => 'unknown_action'), 400);
}
