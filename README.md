# TKProtect for YouTube Morphe

Патч для **YouTube Morphe**, который добавляет в плеер кнопку **TK**. Тап по кнопке открывает
веб-панель TKProtect: она читает live-чат и комментарии и **локально скрывает** спам, скам и
другие нарушающие сообщения через TKCore.

**Регистрация в Google Cloud не нужна** — нет OAuth, client ID, токенов и оплаты. Сообщения
не удаляются с YouTube, а только скрываются в панели.

## Что внутри

| Папка | Назначение |
|-------|-----------|
| `patches/` | исходники патча Morphe (Kotlin + Java), который добавляет кнопку TK |
| `web/` | PHP-модуль панели: `index.php`, `api.php`, `lib.php`, `config.php`, `assets/` |

Патч и веб-модуль — две части одного целого: патч даёт кнопку внутри YouTube, веб-модуль
открывается по этой кнопке и делает всю проверку текста.

## Возможности

- кнопка **TK** в оверлее плеера YouTube Morphe;
- тап → панель во WebView, долгий тап → смена адреса панели (без пересборки);
- чтение live-чата и комментариев через внутренний API YouTube (Innertube);
- проверка текста через TKCore (`easyapi.php?type=botmoderator`), кэш вердиктов;
- локальное скрытие, чекбокс «Показывать скрытые», статистика, журнал, ручная проверка;
- автообновление раз в 5 секунд;
- при ошибке проверки сообщение **не скрывается** (fail-open).

## 1. Сборка `.mpp`

### Через GitHub Actions (рекомендуется)

1. Нажмите **Use this template** (или создайте новый репозиторий и загрузите сюда все файлы).
2. Откройте вкладку **Actions** → **«Build TKProtect .mpp»** → **Run workflow**.
3. Дождитесь окончания и скачайте артефакт `tkprotect-mpp` внизу страницы.
4. Распакуйте — внутри будет `patches-<версия>.mpp`.

### Локально

Нужны Java 21+ и интернет. Задача `buildAndroid` сама подготовит Android SDK.

```powershell
.\gradlew.bat buildAndroid
```

Если `android.jar` уже есть в другом месте:

```powershell
.\gradlew.bat buildAndroid -PandroidJar="I:\path\to\android.jar"
```

> Плагин `app.morphe.patches` лежит в GitHub Packages, поэтому для локальной сборки
> нужен токен с правом `read:packages` (`gpr.user` / `gpr.key` или `GITHUB_ACTOR` / `GITHUB_TOKEN`).
> В GitHub Actions токен подставляется автоматически.

## 2. Установка патча

1. Скопируйте `.mpp` на телефон.
2. Morphe Manager → **Settings → Patches sources → Add source** → выберите файл.
3. При патчинге YouTube включите **«TKProtect panel»**.
4. Перепатчьте YouTube и установите APK.

Поддерживаемые версии YouTube указаны в
`patches/src/main/kotlin/app/tkprotect/patches/youtube/YouTubeCompatibility.kt`.

## 3. Подъём веб-модуля

1. Скопируйте содержимое `web/` на PHP-сервер (PHP 7.4+, расширения **curl**, **json**, **mbstring**).
   В OSPanel это, например, `home/youtube-morphe/`.
2. Скопируйте `web/config.local.php.example` в `web/config.local.php` и укажите свой
   **TKCore API key** (без него проверка не работает).
3. Проверьте, что страница открывается: `http://<адрес-сервера>/youtube-morphe/index.php?v=VIDEO_ID`.

## 4. Настройка кнопки

Долго нажмите кнопку **TK** в плеере и введите полный адрес модуля, доступный с телефона:

```
http://192.168.1.50:8080/youtube-morphe/index.php
```

Адрес хранится в SharedPreferences — пересобирать патч после смены сервера не нужно.

> **HTTP и Android 9+.** WebView может блокировать обычный HTTP (cleartext). Если панель не
> грузится, включите в Morphe патч «разрешить cleartext-трафик» (если доступен) или
> поднимите модуль по HTTPS. Сначала проверьте адрес обычным браузером телефона.

## Обновление Innertube

Внутренний API YouTube неофициальный. Если чат/комментарии перестанут читаться, обновите
`yt_innertube_key` и `yt_client_version` в `web/config.local.php` — актуальные значения
берутся из HTML любого видео YouTube (`INNERTUBE_API_KEY`, `INNERTUBE_CLIENT_VERSION`).

## Лицензия

GPLv3 — см. `LICENSE`.
