# AE2 Telegram Notify (для TerraFirmaGreg-Modern / Minecraft 1.20.1 Forge)

Мод отслеживает заказы автокрафта в **Applied Energistics 2 (AE2 / UELM)**, оформленные игроками, и при их завершении отправляет уведомление в **Telegram** — в том числе если игрок уже вышел с сервера.

## Совместимость
- **Сборка:** TerraFirmaGreg-Modern (`0.13.x` и др.)
- **Minecraft:** `1.20.1`
- **Загрузчик:** Forge (`47.1.0+`, собрано под `47.4.13`)
- **AE2:** Applied Energistics 2 `15.5.9-uelm` (`15.0.0+`)

## Особенности
- **Только заказы игроков**: автоматические крафты шин экспорта / интерфейсов / излучателей уровня не спамят в Telegram.
- **Работает оффлайн**: отслеживание идёт на стороне сервера (и встроенного сервера в одиночной игре) через `CraftingCpuLogic`.
- **Персональная привязка или общий чат**:
  - каждый игрок может привязать свой Telegram командой в игре (`/aenotify link` + `/aenotify verify` или `/aenotify bind <chat_id>`),
  - или можно указать общий `defaultChatId` в конфиге.
- **Асинхронная отправка**: HTTP-запросы в Telegram выполняются в фоновом потоке и не тормозят TPS сервера.

## Установка
1. Скачайте `ae2telegramnotify-forge-1.20.1-1.0.0.jar` со страницы Releases (или соберите через `./gradlew build`).
2. Положите `.jar` в папку `mods/` вашего сервера или клиента TerraFirmaGreg-Modern.
   > Если ставите на выделенный сервер, клиентам устанавливать мод **не обязательно** (`IGNORE_SERVER_VERSION`).

## Настройка бота
1. Создайте бота через `@BotFather` в Telegram и скопируйте токен (`123456789:ABC...`).
2. Запустите сервер/игру один раз, чтобы создался конфиг `config/ae2telegramnotify-common.toml`.
3. Впишите токен в `botToken = "..."` (и при желании `defaultChatId = "..."` для общего чата).
4. **Если сервер находится в РФ / за блокировкой Telegram**, настройте прокси только для запросов к Telegram прямо в `[telegram]` в `ae2telegramnotify-common.toml`:
   ```toml
   proxyType = "SOCKS5" # NONE, SOCKS5 или HTTP
   proxyHost = "1.2.3.4"
   proxyPort = 1080
   proxyUsername = ""   # при необходимости
   proxyPassword = ""   # при необходимости
   ```
   (Либо укажите свой reverse proxy в `apiBaseUrl = "https://..."`.)
5. Перезапустите сервер/игру (или примените конфиг и проверьте в игре командой `/aenotify test`).

## Команды в игре
- `/aenotify link` — выдаёт одноразовый код привязки (на 5 минут). Отправьте его боту в Telegram, затем введите:
- `/aenotify verify` — автоматически находит ваш чат по коду и привязывает его.
- `/aenotify bind <chat_id>` — прямая привязка по числовому `chat_id` (например, для группы `-1001234567890`).
- `/aenotify test` — отправить тестовое сообщение в Telegram.
- `/aenotify toggle` — поставить уведомления на паузу / возобновить.
- `/aenotify status` — показать статус привязки и настроек.
- `/aenotify unbind` — удалить привязку.

Для администраторов (OP уровень 2+):
- `/aenotify admin bind <игрок> <chat_id>`
- `/aenotify admin unbind <игрок>`
- `/aenotify admin status`
