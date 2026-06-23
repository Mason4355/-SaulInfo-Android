# SaulInfo Android

Отдельный Android-проект для пользовательского кабинета SaulInfo.

Основное:
- Kotlin WebView shell для `cabinet-frontend`.
- Debug/release APK и release AAB.
- URL кабинета задаётся через `cabinetUrl`, без жёсткого домена.
- Название сборки задаётся через `androidAppName`, runtime-название подтягивается из `/api/cabinet/branding`.
- Release запрещает HTTP и WebView debugging.

Команды:

```powershell
gradle :android-app:assembleDebug -PcabinetUrl=https://your-domain.example/
gradle :android-app:assembleRelease -PcabinetUrl=https://your-domain.example/
gradle :android-app:bundleRelease -PcabinetUrl=https://your-domain.example/
```

Подробности: [android-app/README.md](android-app/README.md).
