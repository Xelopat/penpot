# Penpot 2.18.5: исправления Plugin API и MCP

Ветка основана на официальной версии 2.18.5. Исправление связывает свойство
`relativeTo` в Plugin API с полем `position-relative-to`, которое читает
редактор и просмотрщик Penpot. Последующие сборки также исправляют:

- перенос привязки окна на скопированный опорный объект при клонировании;
- значение `linear` по умолчанию, когда у анимации не указан `easing`;
- экспорт файла через текущий серверный механизм с ожиданием сохранения;
- номер версии, который Plugin API раньше возвращал как `null`.

В `kordex.3` добавлена фильтрация обсуждений по странице и исправлено
значение `showResolved` по умолчанию. Чтение комментариев больше не должно
дублировать обсуждения на всех страницах файла. Редактирование текста
комментария по-прежнему доступно только его автору.

Оба варианта `File.export('penpot')` и `File.export('zip')` возвращают текущий
ZIP-контейнер формата `.penpot`, как экспорт из интерфейса. Удалённый старый
экспортёр SVG-архивов не используется.

Эти исправления не меняют формат базы или серверный API.
Уже созданные ошибочные взаимодействия могут потребовать повторного задания
привязки через MCP: обновление не переписывает существующие проекты.

## Готовность образа

Образ публикуется **только после успешной сборки, проверки форматирования,
статического анализа и тестов привязок, анимаций и экспорта**. Состояние сборки:
<https://github.com/Xelopat/penpot/actions/workflows/kordex-frontend.yml>.

Имена образов:

- `ghcr.io/xelopat/penpot-frontend:2.18.5-kordex.3` — текущая сборка исправлений;
- `ghcr.io/xelopat/penpot-frontend:2.18.5-kordex.3-<commit>` — конкретный коммит.

Публикуются варианты Linux amd64 и arm64. Первая сборка может занять много
времени: она собирает официальный интерфейс, WASM-рендерер и плагины.

Сборка `2.18.5-kordex.3-b904c07a3f8c` прошла форматирование, статический
анализ и 42 теста с 495 проверками. Образ доступен без авторизации.
Результат: <https://github.com/Xelopat/penpot/actions/runs/41308718549>.
Дайджест образа:
`sha256:2d2815906d43aae20d2695a8d1347fc2df16d24bd5c090bc74da640c8d1b49ec`.

## Установка на существующий сервер

Выполняйте команды в папке **действующей установки** с `docker-compose.yaml`.
Используйте прежнее имя Compose-проекта и прежние параметры `-p`/`--env-file`,
если вы их задавали при запуске. Это исправление предназначено для установки
Penpot **2.18.5**. Для другой версии сначала нужна отдельная сборка.

Не запускайте обновление, пока сборка образа не завершилась успешно.
Для указанной сборки скачивание без авторизации уже проверено. Если будущий
пакет окажется приватным, откройте настройки пакета:
<https://github.com/users/Xelopat/packages/container/penpot-frontend/settings>,
выберите **Change visibility → Public** и подтвердите имя пакета.
Вместо этого можно оставить пакет приватным и авторизоваться на сервере
через `docker login ghcr.io` с токеном, имеющим право `read:packages`.
Правила доступа: <https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry>.

Сначала сохраните текущий образ интерфейса для отката:

```bash
frontend_id=$(docker compose -f docker-compose.yaml ps -q penpot-frontend)
test -n "$frontend_id" || { echo 'Контейнер интерфейса не найден'; exit 1; }
old_image=$(docker inspect --format '{{.Image}}' "$frontend_id")
rollback_image="penpot-kordex-rollback:$(date +%Y%m%d-%H%M%S)"
docker tag "$old_image" "$rollback_image"
printf '%s\n' "$rollback_image" > penpot-frontend-rollback.txt
```

Скачайте отдельное дополнение к вашему Compose-файлу:

```bash
curl -fL 'https://raw.githubusercontent.com/Xelopat/penpot/codex/mcp-overlay-relative-to/docker/kordex/compose.override.yaml' -o docker-compose.kordex.yaml
export KORDEX_FRONTEND_IMAGE=ghcr.io/xelopat/penpot-frontend:2.18.5-kordex.3
docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml pull penpot-frontend
docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml up -d --no-deps penpot-frontend
docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml logs --tail=50 penpot-frontend
```

Если `pull` завершился ошибкой, не выполняйте `up`. Проверьте состояние
сборки и доступность пакета GHCR. Для публичной установки пакет должен быть
публичным; владельцу доступен и вход через `docker login ghcr.io`.

Остальные сервисы и тома эти команды не пересоздают. Интерфейс кратковременно
перезапустится. После запуска обновите вкладку Penpot с очисткой кеша
(`Ctrl+Shift+R`), откройте редактор и заново подключите MCP при необходимости.
Проверьте привязку окна через MCP и его положение в просмотрщике после
прокрутки. Не считайте проверку завершённой только по ответу MCP.

Чтобы последующие запуски сохраняли исправление, добавьте
`KORDEX_FRONTEND_IMAGE=ghcr.io/xelopat/penpot-frontend:2.18.5-kordex.3` в ваш
существующий `.env` и используйте оба файла `-f`. Команда только с исходным
файлом вернёт официальный образ интерфейса.

## Откат интерфейса

```bash
export KORDEX_FRONTEND_IMAGE=$(cat penpot-frontend-rollback.txt)
docker image inspect "$KORDEX_FRONTEND_IMAGE" > /dev/null
docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml up -d --no-deps penpot-frontend
```

Если вы сохраняли настройку в `.env`, замените её и там на имя образа отката.
Откат не требует восстановления базы: данная сборка меняет только интерфейс.

## Дальнейшие версии

Сохраняйте точную официальную базовую версию и отдельный коммит исправления.
При переходе на новую версию перенесите исправление, обновите базовый образ
в Dockerfile, задайте новый тег и дождитесь успешной сборки и проверок.
Не смешивайте интерфейс этой ветки с более новой версией остальных сервисов.
Когда официальная версия исправит ту же причину, дополнительный патч можно
убрать после проверки сценария в просмотрщике.
