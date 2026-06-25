# SaulInfo Android Cabinet

Android WebView-приложение для пользовательского кабинета `cabinet-frontend`.

## Что настроено

- `minSdk 26` — Android 8+.
- Debug и release сборки APK/AAB.
- URL кабинета через `BuildConfig.CABINET_URL`.
- API-ключ через `BuildConfig.ANDROID_APP_API_KEY`.
- Название сборки через `androidAppName`, runtime-название подтягивается из `/api/cabinet/mobile/config`.
- Cookies, local/session storage и авторизация WebView.
- File picker для загрузки файлов и изображений.
- DownloadManager для скачивания файлов.
- Внешние схемы: `tg://`, `mailto:`, `intent://`, `vless://`, `happ://`.
- Запрет `/admin*` внутри Android WebView.
- Release не разрешает HTTP и WebView debugging.
- SSL-ошибки не игнорируются.
- Network Security Config с запретом cleartext.

## Локальная конфигурация

Создайте в корне репозитория `local.properties`:

```properties
androidApplicationId=ru.saulinfo.cabinet
androidAppName=SaulInfo
androidAppApiKey=YOUR_KEY
androidPushTopic=broadcasts
cabinetUrl=https://your-domain.example/cabinet/
versionCode=1
versionName=1.0.0

# Только для release-подписи
SAULINFO_KEYSTORE_FILE=D:\keys\saulinfo-cabinet.jks
SAULINFO_KEYSTORE_PASSWORD=change-me
SAULINFO_KEY_ALIAS=saulinfo-cabinet
SAULINFO_KEY_PASSWORD=change-me
```

## Сборка

```powershell
gradle :android-app:assembleDebug
gradle :android-app:assembleRelease
gradle :android-app:bundleRelease
```

Артефакты:

- Debug APK: `android-app/build/outputs/apk/debug/android-app-debug.apk`
- Release APK: `android-app/build/outputs/apk/release/android-app-release.apk`
- Release AAB: `android-app/build/outputs/bundle/release/android-app-release.aab`

## Параметры без local.properties

```powershell
gradle :android-app:assembleDebug `
  -PandroidApplicationId=ru.saulinfo.cabinet `
  -PandroidAppName=SaulInfo `
  -PcabinetUrl=https://your-domain.example/cabinet/ `
  -PandroidAppApiKey=YOUR_KEY `
  -PandroidPushTopic=broadcasts
```

## Firebase push

1. In Firebase Console create Android app with the same package name as `androidApplicationId`.
2. Download `google-services.json` and put it into `android-app/google-services.json`.
3. In admin panel enable Android push, paste Firebase Project ID and Service Account JSON.
4. Keep the same topic in admin panel and APK build. Default topic is `broadcasts`.

Debug APK uses the same package name by default. If you build with `-PdebugApplicationIdSuffix=.debug`, add a second Firebase Android app for `your.package.debug`.

## Keystore

Генерацию ключа для публикации лучше делать отдельным этапом в админ-панели. До этого можно создать локальный тестовый ключ:

```powershell
keytool -genkeypair -v `
  -keystore D:\keys\saulinfo-cabinet.jks `
  -alias saulinfo-cabinet `
  -keyalg RSA -keysize 4096 -validity 10000
```

Keystore, пароли и `local.properties` не коммитятся.

## GitHub Actions

Workflow `.github/workflows/android-build.yml` собирает debug APK всегда. Release APK/AAB собираются только при наличии secrets:

- `ANDROID_KEYSTORE_BASE64`
- `SAULINFO_KEYSTORE_PASSWORD`
- `SAULINFO_KEY_ALIAS`
- `SAULINFO_KEY_PASSWORD`
- `CABINET_URL`
- `ANDROID_APP_API_KEY`

## Следующий этап

- Firebase push-уведомления.
- Android App Links с `assetlinks.json`.
- Смена домена внутри приложения, если это понадобится владельцам.
