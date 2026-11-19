<?php
// Front-end shell for the Morphe YouTube / TKProtect moderation panel.
header('Content-Type: text/html; charset=utf-8');
?>
<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Morphe YouTube · TKProtect</title>
  <link rel="stylesheet" href="assets/styles.css">
</head>
<body>
<div class="tkp-app">
  <header class="tkp-header">
    <div class="tkp-brand">
      <span class="tkp-shield">TK</span>
      <div>
        <h1>Morphe YouTube <span>· TKProtect</span></h1>
        <p class="tkp-subtitle">Локальное скрытие сообщений и комментариев трансляции</p>
      </div>
    </div>
    <div class="tkp-account" id="account-box">
      <span class="tkp-account__name" id="account-name">TKProtect</span>
    </div>
  </header>

  <section class="tkp-card tkp-connect">
    <label for="video-id">ID видео / трансляции (11 символов)</label>
    <div class="tkp-connect__row">
      <input id="video-id" type="text" maxlength="11" placeholder="например: dQw4w9WgXcQ" autocomplete="off" spellcheck="false">
      <button class="tkp-btn tkp-btn--primary" id="connect-btn">Подключиться</button>
    </div>
    <div class="tkp-video-info" id="video-info" hidden></div>
    <p class="tkp-hint" id="setup-hint" hidden>
      Укажите ваш TKCore API key в <code>config.local.php</code> — без него проверка текста не работает.
      Регистрация приложения в Google не требуется.
    </p>
  </section>

  <nav class="tkp-tabs" role="tablist">
    <button class="tkp-tab is-active" data-tab="chat" role="tab">Чат стрима</button>
    <button class="tkp-tab" data-tab="comments" role="tab">Комментарии</button>
    <button class="tkp-tab" data-tab="protect" role="tab">TKProtect</button>
  </nav>

  <main class="tkp-card tkp-panel">
    <section class="tkp-view is-active" data-view="chat">
      <div class="tkp-view__head">
        <h2>Сообщения чата</h2>
        <span class="tkp-badge" id="chat-count">0</span>
      </div>
      <div class="tkp-list" id="chat-list"><p class="tkp-empty">Введите ID видео и нажмите «Подключиться».</p></div>
    </section>

    <section class="tkp-view" data-view="comments">
      <div class="tkp-view__head">
        <h2>Комментарии под видео</h2>
        <span class="tkp-badge" id="comments-count">0</span>
      </div>
      <div class="tkp-list" id="comments-list"><p class="tkp-empty">Комментарии появятся после подключения.</p></div>
    </section>

    <section class="tkp-view" data-view="protect">
      <div class="tkp-grid">
        <div class="tkp-block">
          <h3>Проверка текста</h3>
          <p class="tkp-muted">Тот же API, что используется в superbotmoderator.php (<code>type=botmoderator</code>).</p>
          <form id="check-form">
            <textarea id="check-text" rows="3" placeholder="Введите сообщение для проверки"></textarea>
            <button class="tkp-btn tkp-btn--primary" type="submit">Проверить</button>
          </form>
          <div class="tkp-check-result" id="check-result" hidden></div>
        </div>

        <div class="tkp-block">
          <h3>Статистика сессии</h3>
          <ul class="tkp-stats">
            <li>Проверено сообщений: <strong id="stat-checked">0</strong></li>
            <li>Скрыто локально: <strong id="stat-hidden">0</strong></li>
          </ul>
          <label class="tkp-toggle"><input type="checkbox" id="auto-poll" checked> Автообновление каждые 5 секунд</label>
          <label class="tkp-toggle"><input type="checkbox" id="force-show"> Показывать скрытые сообщения</label>
        </div>
      </div>

      <div class="tkp-block">
        <h3>Журнал действий</h3>
        <ul class="tkp-log" id="log"><li class="tkp-muted">Журнал пуст.</li></ul>
      </div>
    </section>
  </main>

  <footer class="tkp-footer">
    Нарушающие сообщения и комментарии скрываются только в этом браузере.
    Удаление на YouTube не выполняется — регистрация приложения в Google не нужна.
  </footer>
</div>
<script src="assets/app.js" defer></script>
</body>
</html>
