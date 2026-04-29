# TG WS Proxy Android (MVP)

Этот модуль добавляет Android-версию интерфейса для `tg-ws-proxy` с локальным (оффлайн) хранением конфигурации:

- host / port / secret
- список `DC:IP`
- генерация `tg://proxy` ссылки
- кнопка открытия Telegram через `Intent`

## Что уже реализовано

1. Android проект на Kotlin + Jetpack Compose.
2. Экран управления в стиле desktop-потока: параметры прокси + действия.
3. Локальный JSON-конфиг в private storage (`filesDir`), без внешних API.
4. Основа для полноценного переноса UX в Android.

## Важно

Текущий MVP закрывает UI + offline-configuration часть.  
Следующий шаг — встраивание proxy-движка (`proxy/*.py`) в Android runtime
(например через встроенный Python runtime/NDK-сервис) и запуск его как foreground service.

## Сборка

Откройте папку `android` в Android Studio и выполните стандартный build/run.

