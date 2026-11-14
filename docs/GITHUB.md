# Публичный репозиторий

Для этого проекта подойдёт один репозиторий `pinch-music`. В нём обе платформы используют один Kotlin-модуль и одну папку с оригинальными ресурсами. В описании можно написать «В такт — мобильная ритм-игра на Kotlin Multiplatform для iOS и Android».

## Первый push с Mac

Распакуй архив и открой терминал в папке `pinch-music`, рядом с `gradlew`. Первый блок только готовит локальный коммит. Проверь список в `git status`, включая `.github/workflows`, `gradle/wrapper` и `media`.

```bash
git init -b main
git add .
git status --short
git commit -m "Add playable rhythm game for iOS and Android"
```

С установленным GitHub CLI проверь аккаунт командой `gh auth status`. Для первого входа используй `gh auth login`. Следующая команда создаёт публичный репозиторий у текущего владельца и сразу отправляет коммит.

```bash
gh repo create pinch-music --public --source=. --remote=origin --push \
  --description "В такт — ритм-игра на Kotlin Multiplatform для iOS и Android"
```

Через сайт GitHub можно вместо этой команды создать пустой репозиторий `pinch-music` с видимостью **Public**. Сними галочки создания README, .gitignore и лицензии, чтобы первый push прошёл с уже подготовленной историей. В той же папке выполни команды, подставив URL своего репозитория.

```bash
git remote add origin https://github.com/YOUR-ACCOUNT/pinch-music.git
git push -u origin main
```

## Настройки после первого push

| Где | Что включить |
| --- | --- |
| Settings → General → Pull Requests | Squash merging и автоматическое удаление влитых веток. |
| Actions → Mobile | Дождись первого запуска Android build and tests и iOS Simulator build. У первого job есть артефакт с debug APK. Второй собирает приложение для симулятора без ключа подписи и проверяет наличие медиа в пакете. |
| Settings → Rules → Rulesets | Создай активное правило для `main`. Включи запрет удаления и force push, Require a pull request before merging и Require status checks to pass. Выбери обе проверки по точным именам из Actions после их первого появления. |
| Required approvals | Для одного разработчика поставь 0. Когда появится второй постоянный участник, при желании поставь 1. |
| Settings → Actions → General | Достаточно read-only доступа к содержимому. Workflow уже задаёт `contents: read`; ключи подписи для этих сборок не нужны. |

Дальше удобно делать короткие ветки, например `work/ios-audio` для проверки звука. Демо-ролики и samples уже помещаются в обычный Git. Храни подписанные релизы в Releases, а локальные файлы Xcode, ключи и APK отсекает `.gitignore`.

Публичность даёт возможность посмотреть проект и сделать fork на GitHub. Права на повторное использование кода и демо за пределами правил платформы зависят от лицензии. Лицензию для проекта выбирает владелец, отдельно решая, распространяется ли она на музыку и видео. Включённый Gradle Wrapper поставляется на собственных условиях Apache 2.0.

[GitHub CLI](https://cli.github.com/manual/gh_repo_create) · [Защита веток](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches) · [Лицензирование](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
