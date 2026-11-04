# Penpot 2.18.5: форк Kordex (прототипы, Plugin API и MCP)

Ветка основана на официальной версии 2.18.5. Ранние сборки исправили Plugin API:
привязку окна `relativeTo`, её перенос при клонировании, `linear` по умолчанию
для анимаций, экспорт файла, номер версии и чтение комментариев по странице.

## kordex.4: прототипы ближе к Figma

- **Окна относительно экрана.** Позиции «по центру», «снизу по центру» и другие
  считаются от видимой области, а не от всей длинной доски. Окно остаётся на
  месте при прокрутке страницы под ним. В редакторе это пункт «Экран» в поле
  «Относительно», в Plugin API — `relativeTo: 'viewport'`. Если `relativeTo` не
  указан, Plugin API использует экран, кроме ручной позиции.
- **Прокрутка к блоку** (`scroll-to`): якорные ссылки меню с плавной прокруткой
  и отступом под закреплённую шапку. Цель на другой доске — переход и
  прокрутка; цель внутри прокручиваемого блока — прокрутка этого блока.
- **Замена окна** (`swap-overlay`): вкладки, аккордеоны и шаги не копятся
  стопкой. Старое окно исчезает только после появления нового.
- **Smart animate**: слои с одинаковыми именами плавно меняют положение, размер
  и прозрачность при переходе между экранами и при замене окна; слои с другим
  видом растворяются друг в друге.
- **Наведение без мигания**: «При наведении» не закрывает окно, пока указатель
  над ним, а клики по состоянию наведения доходят до элемента под ним.
- **Анимация по умолчанию**: новые переходы получают растворение 250 мс
  (150 мс для наведения), прокрутка — 400 мс.
- **Прокрутка внутри блока**: вложенная доска с параметром «Прокрутка»
  (по горизонтали, по вертикали или в обе стороны) листается колесом,
  тачпадом, свайпом и перетаскиванием мышью, с привязкой к элементам по желанию.
  Фон доски остаётся на месте.

Анимации и прокрутка блоков работают в обычном (SVG) просмотрщике. В
экспериментальном WASM-просмотрщике smart animate заменяется растворением, а
прокрутка блоков не поддерживается.

### Что меняется на сервере

Обновляются **два образа: интерфейс и backend**. Backend 2.18.5 проверяет
каждую вставленную или продублированную фигуру по схеме из общего кода.
Официальный образ отклоняет новые действия и smart animate: это проверено на
официальном `penpot.jar` в сборке. Поэтому backend-образ форка — это официальный
`penpotapp/backend:2.18.5`, в котором заменён только `penpot.jar`, собранный из
того же коммита. Код сервера, миграции базы и номер версии (`2.18.5-RC1`) не
меняются. Сборка проверяет, что новый `penpot.jar` принимает такие изменения.

Exporter, MCP-сервер и остальные официальные сервисы не меняются. Держите их на
**2.18.5**, а не `latest` и не `2.18`. Иначе новый официальный backend прогонит
миграции под свою версию и разойдётся с интерфейсом форка.

## Образы

Образы публикуются **только после успешной сборки и тестов**:

| Сервис | Плавающий тег | Версия | Конкретный коммит |
|---|---|---|---|
| Интерфейс | `ghcr.io/xelopat/penpot-frontend:kordex` | `…:2.18.5-kordex.4` | `…:2.18.5-kordex.4-<commit>` |
| Backend | `ghcr.io/xelopat/penpot-backend:kordex` | `…:2.18.5-kordex.4` | `…:2.18.5-kordex.4-<commit>` |
| MCP | `ghcr.io/xelopat/penpot-mcp:kordex` | `…:2.18.5-kordex.2` | `…:2.18.5-kordex.2-<commit>` |

Сборки: [интерфейс](https://github.com/Xelopat/penpot/actions/workflows/kordex-frontend.yml),
[backend](https://github.com/Xelopat/penpot/actions/workflows/kordex-backend.yml),
[MCP](https://github.com/Xelopat/penpot/actions/workflows/kordex-mcp.yml).
Они запускаются при пуше в ветку `codex/mcp-overlay-relative-to`.
Публикуются варианты Linux amd64 и arm64. Если пакет приватный, сделайте его
публичным в настройках пакета на GitHub или выполните на сервере
`docker login ghcr.io` с токеном с правом `read:packages`.

## Установка на существующий сервер

Выполняйте команды в папке **действующей установки** с `docker-compose.yaml`,
с прежним именем Compose-проекта и прежними `-p`/`--env-file`, если они были.
Не запускайте обновление, пока сборки интерфейса и backend не завершились
успешно.

1. Зафиксируйте официальные образы в `.env`, чтобы они не обновились сами:

   ```bash
   grep -q '^PENPOT_VERSION=' .env && sed -i 's/^PENPOT_VERSION=.*/PENPOT_VERSION=2.18.5/' .env || echo 'PENPOT_VERSION=2.18.5' >> .env
   ```

   Если в вашем `docker-compose.yaml` версии образов записаны прямо, проверьте,
   что exporter, MCP и остальные сервисы указаны как `2.18.5`.

2. Сохраните текущие образы для отката и сделайте резервную копию базы:

   ```bash
   for s in penpot-frontend penpot-backend; do
     id=$(docker compose ps -q "$s"); test -n "$id" || { echo "Нет контейнера $s"; exit 1; }
     tag="penpot-kordex-rollback-$s:$(date +%Y%m%d-%H%M%S)"
     docker tag "$(docker inspect --format '{{.Image}}' "$id")" "$tag" && echo "$tag" >> penpot-rollback.txt
   done
   docker compose exec -T penpot-postgres pg_dump -U penpot penpot | gzip > penpot-before-kordex4.sql.gz
   ```

3. Скачайте дополнение к Compose-файлу (интерфейс и backend на теге `:kordex`
   с `pull_policy: always`) и обновите оба сервиса:

   ```bash
   curl -fL 'https://raw.githubusercontent.com/Xelopat/penpot/codex/mcp-overlay-relative-to/docker/kordex/compose.override.yaml' -o docker-compose.kordex.yaml
   docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml pull penpot-frontend penpot-backend
   docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml up -d --no-deps penpot-backend penpot-frontend
   docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml logs --tail=50 penpot-backend penpot-frontend
   ```

   Если `pull` завершился ошибкой, не выполняйте `up`. Дальше всегда используйте
   оба файла `-f`: команда только с исходным файлом вернёт официальные образы.

4. Обновите вкладку Penpot с очисткой кеша (`Ctrl+Shift+R`) и при необходимости
   заново подключите MCP. Проверьте в просмотрщике окно после прокрутки длинной
   страницы, вкладки с заменой окна, якорную ссылку и дублирование слоя с новым
   действием: дублирование проверяет схему backend.

### Последующие обновления

Тег `:kordex` перемещается после каждой успешной сборки:

```bash
docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml pull penpot-frontend penpot-backend
docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml up -d --no-deps penpot-frontend penpot-backend
```

Watchtower можно использовать только как опцию и только для этих двух
сервисов: включите `--label-enable` и добавьте им метку
`com.centurylinklabs.watchtower.enable=true`. Официальные сервисы им не
обновляйте.

## Откат

На конкретный коммит форка (теги видны на странице пакета или в итогах сборки):

```bash
export KORDEX_FRONTEND_IMAGE=ghcr.io/xelopat/penpot-frontend:2.18.5-kordex.4-<commit>
export KORDEX_BACKEND_IMAGE=ghcr.io/xelopat/penpot-backend:2.18.5-kordex.4-<commit>
docker compose -f docker-compose.yaml -f docker-compose.kordex.yaml up -d --no-deps penpot-frontend penpot-backend
```

На сохранённые до обновления образы: подставьте в те же переменные строки из
`penpot-rollback.txt`. Чтобы откат сохранился после перезапуска, запишите
переменные в `.env`.

Откат не требует восстановления базы: схема данных и миграции не менялись.
Но файлы, где уже есть новые действия (`scroll-to`, `swap-overlay`, smart
animate), официальный backend не даст дублировать или вставлять. Официальный
интерфейс эти действия не воспроизводит. Перед возвратом на официальные образы
замените такие взаимодействия.

## Дальнейшие версии

Сохраняйте точную официальную базовую версию и отдельный коммит исправления.
При переходе на новую официальную версию перенесите исправления, обновите
базовые образы в `Dockerfile` и `Dockerfile.backend`, задайте новые теги и
дождитесь успешной сборки. Не смешивайте образы форка с более новой версией
остальных сервисов.
