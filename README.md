# SaulInfo Android

Отдельный Android-проект для пользовательского кабинета SaulInfo.

Основное:
- Kotlin WebView shell для `cabinet-frontend`.
- Debug/release APK и release AAB.
- URL кабинета задаётся через `cabinetUrl`, без жёсткого домена.
- API-ключ приложения задаётся через `androidAppApiKey`.
- Название сборки задаётся через `androidAppName`, runtime-название подтягивается из `/api/cabinet/mobile/config`.
- Release запрещает HTTP и WebView debugging.

Команды:

```powershell
gradle :android-app:assembleDebug -PcabinetUrl=https://your-domain.example/cabinet/ -PandroidAppApiKey=YOUR_KEY
gradle :android-app:assembleRelease -PcabinetUrl=https://your-domain.example/cabinet/ -PandroidAppApiKey=YOUR_KEY
gradle :android-app:bundleRelease -PcabinetUrl=https://your-domain.example/cabinet/ -PandroidAppApiKey=YOUR_KEY
```

Порядок:
1. Обновить сервер и кабинет.
2. В админке открыть `Приложения`.
3. В блоке `Android-приложение` указать URL кабинета, название и package name.
4. Нажать `Сгенерировать API-ключ`.
5. Собрать APK/AAB командой из админки.

Подробности: [android-app/README.md](android-app/README.md).