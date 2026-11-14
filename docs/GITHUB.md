# Работа с репозиторием

Из нового каталога на Mac можно открыть текущую сборку так:

```bash
git clone https://github.com/NolReaction/pinch-music.git
cd pinch-music
open iosApp/iosApp.xcodeproj
```

Xcode использует локальную Personal Team владельца. При первом запуске на физическом устройстве выбери команду в Signing & Capabilities. Рекомендуемая версия Java, выбор эмулятора и команды сборки даны в [README](../README.md).

## Проверки

Workflow [Mobile](https://github.com/NolReaction/pinch-music/actions/workflows/mobile.yml) запускается при push в main, pull request или ручном запуске. В Linux собирается debug APK и запускаются тесты. На macOS собирается приложение для симулятора и проверяется наличие звуковых ресурсов. APK можно скачать из артефакта успешной сборки. Для установки на iPhone с Mac используется Xcode.

Для следующих изменений удобно работать в короткой ветке и вливать через pull request. В настройках GitHub для main можно включить обязательные проверки Android build and tests и iOS Simulator build после их успешного первого запуска. Пользовательские аудиофайлы импортируются непосредственно на телефоне.
