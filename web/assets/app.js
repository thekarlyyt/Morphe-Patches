// Morphe YouTube / TKProtect - front-end controller (hide-only mode).
'use strict';

const state = {
  csrf: '',
  moderationReady: false,
  videoId: '',
  chatItems: [],
  commentItems: [],
  stats: { checked: 0, hidden: 0 },
  settings: { autoPoll: true, forceShow: false },
};

const $ = (selector, root) => (root || document).querySelector(selector);
const $$ = (selector, root) => Array.from((root || document).querySelectorAll(selector));

function make(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
}

function formatTime(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

function writeLog(message, isError) {
  const log = $('#log');
  const first = log.querySelector('.tkp-muted');
  if (first) first.remove();
  const item = make('li', isError ? 'tkp-log__item tkp-log__item--error' : 'tkp-log__item',
    new Date().toLocaleTimeString('ru-RU') + ' — ' + message);
  log.insertBefore(item, log.firstChild);
  while (log.children.length > 40) log.removeChild(log.lastChild);
}

async function api(action, data, method) {
  const verb = method || 'GET';
  const params = data || {};
  let url = 'api.php?action=' + encodeURIComponent(action);
  const options = { method: verb, credentials: 'same-origin', headers: {} };

  if (verb === 'GET') {
    Object.keys(params).forEach((key) => {
      if (params[key] !== '' && params[key] !== null && params[key] !== undefined) {
        url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(params[key]);
      }
    });
  } else {
    const body = new FormData();
    Object.keys(params).forEach((key) => body.append(key, params[key]));
    body.append('csrf', state.csrf);
    options.body = body;
    options.headers['X-TKP-CSRF'] = state.csrf;
  }

  const response = await fetch(url, options);
  let payload;
  try {
    payload = await response.json();
  } catch (error) {
    throw new Error('Некорректный ответ API');
  }
  if (!response.ok) {
    throw new Error(payload.error || ('HTTP ' + response.status));
  }
  return payload;
}

function verdictBadge(verdict) {
  if (!verdict || verdict.type === 'allowed' || verdict.type === 'pending') return null;
  const cls = verdict.bad ? 'tkp-verdict tkp-verdict--bad' : 'tkp-verdict';
  const badge = make('span', cls, verdict.label || verdict.type);
  if (verdict.reason) badge.title = String(verdict.reason);
  return badge;
}

function renderItem(item, container) {
  const isHidden = item.hidden && !state.settings.forceShow;
  const row = make('article', 'tkp-item' + (isHidden ? ' tkp-item--hidden' : ''));
  row.dataset.id = item.id;

  const meta = make('div', 'tkp-item__meta');
  meta.appendChild(make('span', 'tkp-author', item.author || 'Аноним'));
  if (item.isOwner) meta.appendChild(make('span', 'tkp-tag tkp-tag--owner', 'владелец чата'));
  if (item.isModerator) meta.appendChild(make('span', 'tkp-tag tkp-tag--mod', 'модератор'));
  if (item.publishedAt) meta.appendChild(make('span', 'tkp-time', formatTime(item.publishedAt)));
  row.appendChild(meta);

  const text = make('div', 'tkp-item__text');
  if (isHidden) {
    text.classList.add('tkp-item__text--masked');
    text.textContent = 'Скрыто TKProtect';
  } else {
    text.textContent = item.text || '';
  }
  row.appendChild(text);

  if (!isHidden) {
    const badge = verdictBadge(item.verdict);
    if (badge) row.appendChild(badge);
  }
  container.appendChild(row);
}

function renderList(target, items) {
  const container = $(target);
  container.innerHTML = '';
  if (!items || items.length === 0) {
    container.appendChild(make('p', 'tkp-empty', 'Список пуст.'));
    return;
  }
  items.forEach((item) => renderItem(item, container));
}

function mergeItems(existing, incoming) {
  const seen = new Set(existing.map((item) => item.id).filter(Boolean));
  const result = existing.slice();
  incoming.forEach((item) => {
    if (item.id && seen.has(item.id)) return;
    if (item.id) seen.add(item.id);
    result.push(item);
  });
  if (result.length > 300) return result.slice(-300);
  return result;
}

function updateStats() {
  const all = state.chatItems.concat(state.commentItems);
  state.stats.checked = all.length;
  state.stats.hidden = all.filter((item) => item.hidden).length;
  $('#stat-checked').textContent = String(state.stats.checked);
  $('#stat-hidden').textContent = String(state.stats.hidden);
}

function showVideoInfo(video) {
  if (!video) return;
  const info = $('#video-info');
  info.hidden = false;
  info.textContent = (video.title || 'Без названия') + ' · канал ' + (video.channelId || '—');
}

async function loadChat(reset) {
  try {
    const data = await api('chat', { videoId: state.videoId, reset: reset ? '1' : '' });
    state.chatItems = reset ? data.messages : mergeItems(state.chatItems, data.messages);
    renderList('#chat-list', state.chatItems);
    $('#chat-count').textContent = String(state.chatItems.length);
    showVideoInfo(data.video);
    updateStats();
    if (data.error) writeLog('Чат: ' + data.error, true);
    if (!data.moderationReady) $('#setup-hint').hidden = false;
  } catch (error) {
    writeLog('Чат: ' + error.message, true);
  }
}

async function loadComments(reset) {
  try {
    const data = await api('comments', { videoId: state.videoId, reset: reset ? '1' : '' });
    state.commentItems = reset ? data.comments : mergeItems(state.commentItems, data.comments);
    renderList('#comments-list', state.commentItems);
    $('#comments-count').textContent = String(state.commentItems.length);
    showVideoInfo(data.video);
    updateStats();
    if (data.error) writeLog('Комментарии: ' + data.error, true);
    if (!data.moderationReady) $('#setup-hint').hidden = false;
  } catch (error) {
    writeLog('Комментарии: ' + error.message, true);
  }
}

function currentTab() {
  const tab = $('.tkp-tab.is-active');
  return tab ? tab.dataset.tab : 'chat';
}

async function loadActive(reset) {
  if (!state.videoId) return;
  const tab = currentTab();
  if (tab === 'chat') await loadChat(reset);
  else if (tab === 'comments') await loadComments(reset);
}

function switchTab(name) {
  $$('.tkp-tab').forEach((tab) => tab.classList.toggle('is-active', tab.dataset.tab === name));
  $$('.tkp-view').forEach((view) => view.classList.toggle('is-active', view.dataset.view === name));
  loadActive(false);
}

async function bootstrap() {
  try {
    const data = await api('bootstrap');
    state.csrf = data.csrf;
    state.moderationReady = !!data.moderationReady;
    $('#setup-hint').hidden = state.moderationReady;
    $('#account-name').textContent = state.moderationReady ? 'TKProtect активен' : 'TKProtect: нет ключа';
    writeLog(state.moderationReady ? 'TKProtect готов к проверке' : 'Укажите TKCore API key в config.local.php', !state.moderationReady);
  } catch (error) {
    writeLog('Не удалось загрузить состояние: ' + error.message, true);
  }
}

$('#connect-btn').addEventListener('click', () => {
  const input = $('#video-id');
  const value = input.value.trim();
  if (!/^[A-Za-z0-9_-]{11}$/.test(value)) {
    input.focus();
    writeLog('ID видео должен содержать 11 символов', true);
    return;
  }
  const changed = state.videoId !== value;
  state.videoId = value;
  state.chatItems = [];
  state.commentItems = [];
  $('#chat-list').innerHTML = '<p class="tkp-empty">Загрузка...</p>';
  $('#comments-list').innerHTML = '<p class="tkp-empty">Загрузка...</p>';
  $('#video-info').hidden = true;
  writeLog('Подключено к видео ' + value);
  loadActive(changed);
});

$('#video-id').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') $('#connect-btn').click();
});

$$('.tkp-tab').forEach((tab) => tab.addEventListener('click', () => switchTab(tab.dataset.tab)));

$('#check-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = $('#check-text').value.trim();
  if (!text) return;
  const result = $('#check-result');
  result.hidden = false;
  result.textContent = 'Проверка...';
  try {
    const verdict = await api('check', { text }, 'POST');
    result.className = 'tkp-check-result ' + (verdict.bad ? 'tkp-check-result--bad' : 'tkp-check-result--ok');
    result.textContent = verdict.label + (verdict.reason ? ' (' + verdict.reason + ')' : '');
  } catch (error) {
    result.className = 'tkp-check-result tkp-check-result--bad';
    result.textContent = error.message;
  }
});

$('#auto-poll').addEventListener('change', (event) => {
  state.settings.autoPoll = event.target.checked;
});

$('#force-show').addEventListener('change', (event) => {
  state.settings.forceShow = event.target.checked;
  renderList('#chat-list', state.chatItems);
  renderList('#comments-list', state.commentItems);
});

setInterval(() => {
  if (state.settings.autoPoll && state.videoId && !document.hidden) loadActive(false);
}, 5000);

const params = new URLSearchParams(window.location.search);
const initialVideo = params.get('v');
if (initialVideo) $('#video-id').value = initialVideo;

bootstrap();
