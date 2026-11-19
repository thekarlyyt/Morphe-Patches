<?php
// Configuration for the Morphe YouTube / TKProtect module (hide-only mode).
// Copy this file to config.local.php and fill in your own values if needed.
// This file merges config.local.php at the end.

$config = array(
    // TKCore gateway used by superbotmoderator.php (phrase moderation).
    'tk_api_base' => 'https://thekarly.su/wp-content/plugins/tkcore/easyapi.php',
    'tk_api_key'  => 'YOUR_TKCORE_API_KEY',
    'tk_mode'     => '0',   // phrase2 / mode passed to bwl1.0.php or bwl2.0.php
    'tk_version'  => '1.0', // 1.0 or 2.0

    // YouTube internal (Innertube) client used to read public chat and comments.
    // No Google Cloud registration is required: this is the key the YouTube web
    // client itself uses. Override in config.local.php if YouTube changes it.
    'yt_innertube_key'     => 'AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8',
    'yt_client_name'       => 'WEB',
    'yt_client_version'    => '2.20250312.04.00',
    'yt_language'          => 'ru',

    // How many chat messages / comments are checked per poll via TKCore.
    'check_limit_per_poll' => 12,
    // NLP verdict cache lifetime in seconds.
    'verdict_ttl' => 300,
    // YouTube metadata cache lifetime in seconds.
    'video_ttl' => 60,
    // HTTP timeouts for YouTube requests.
    'yt_timeout' => 20,
);

if (file_exists(__DIR__ . '/config.local.php')) {
    $local = require __DIR__ . '/config.local.php';
    if (is_array($local)) {
        $config = array_merge($config, $local);
    }
}

return $config;
