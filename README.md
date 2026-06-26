# SaulInfo Android Template

Шаблон Android-приложения для пользовательского кабинета. Репозиторий общий, но каждая установка должна собирать свой APK из fork владельца.

Основное:
- Kotlin WebView shell для `cabinet-frontend`.
- Debug/release APK и release AAB.
- URL кабинета задаётся через `cabinetUrl`, без жёсткого домена.
- API-ключ приложения задаётся через `androidAppApiKey`.
- Название сборки задаётся через `androidAppName`, runtime-название подтягивается из `/api/cabinet/mobile/config`.
- Release запрещает HTTP и WebView debugging.
- Firebase не общий: каждый владелец создаёт свой Firebase project, свой `google-services.json` и свой Service Account JSON.

Правильная схема для владельца:
1. Сделать fork этого Android-репозитория.
2. В админке проекта указать fork в поле `GitHub repository владельца`.
3. Оставить `Upstream repository шаблона` на главный шаблон.
4. Вставить свой `google-services.json` из своего Firebase.
5. Нажать `Отправить настройки в GitHub`.
6. Нажать `Обновить fork и собрать APK`.

Так APK берёт код из общего шаблона, но домен, название, API-ключ, package name и Firebase остаются данными конкретного владельца.

Команды:

```powershell
gradle :android-app:assembleDebug -PcabinetUrl=https://your-domain.example/cabinet/ -PandroidAppApiKey=YOUR_KEY
gradle :android-app:assembleRelease -PcabinetUrl=https://your-domain.example/cabinet/ -PandroidAppApiKey=YOUR_KEY
gradle :android-app:bundleRelease -PcabinetUrl=https://your-domain.example/cabinet/ -PandroidAppApiKey=YOUR_KEY
```

Локальные команды ниже нужны только как запасной вариант. Основной путь: сборка через GitHub Actions в fork владельца.

Подробности: [android-app/README.md](android-app/README.md).
