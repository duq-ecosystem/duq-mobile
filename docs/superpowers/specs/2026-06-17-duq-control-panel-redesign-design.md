# DUQ Android — редизайн: чат → командный пульт движка

**Дата:** 2026-06-17
**Статус:** дизайн на ревью

> ⚠️ УСТАРЕЛО. Этот спек проектировался под прежний движок и его WS-RPC-протокол
> (семейства методов `agents.*`, `cron.*`, `config.schema`, `update.run` и т.д.).
> Текущее ядро — Python-бэкенд (`core/`, контейнер `duq-core`), у которого этих
> RPC нет; сетевой слой клиента — `network/duq/` (`DuqChatClient`/`DuqRestClient`,
> REST + reasoning WS под `/duq/*`). Перечисленные ниже RPC-методы и раздел §6
> к текущему ядру НЕ применимы — документ оставлен как историческая запись
> продуктового замысла «пульта управления», не как актуальная спецификация.

**Решения Danny:** скелет Чат+Пульт+Лента+command palette; утка и тёмный неон
сохраняются; glassmorphism точечно; **только реальный API движка, никаких
плейсхолдеров**; умный дом исключён (нет нативного API).

---

## 1. Цель

Превратить DUQ из голосового чат-клиента в **полноценный пульт управления
движком**, не теряя текущий голосовой UX (утка, push-to-talk, TTS,
inline tool-use). Всё управление строится **исключительно на реальных WS-RPC
методах** gateway (`docs/gateway/protocol.md`, секция «Common RPC method
families»). Если метода нет — раздела нет.

## 2. Принципы

- **Только реальный API.** Каждый экран маппится на конкретные RPC. Нет данных
  от API → нет экрана. (Поэтому «умный дом» исключён — у движка нет нативного
  smart-home API; вернётся только когда появится MCP/tool-интеграция.)
- **Чат — доминанта.** Голосовой UX остаётся ядром, пульт его не вытесняет.
- **Эволюция стиля, не революция.** Утка (`DuqDuck`), палитра `DuqColors`
  (жёлто-оранжевый неон на чёрном) сохраняются. Glassmorphism — точечно
  (оверлеи, bottom sheets, floating-log), плотный непрозрачный фон — для списков
  и форм (читаемость).
- **Material 3.** Bottom navigation (3 вкладки), bottom sheets для create/edit,
  свайп-экшены, продуманные empty states.
- **HITL на риске.** Биометрия/подтверждение перед: секретами, рестартом
  движка, отзывом токенов, разрешением exec-аппрувов, тратами.
- **Прогрессивное раскрытие.** Плитка показывает 1 живую метрику; детали — глубже.

## 3. Информационная архитектура

### Нижняя навигация (3 вкладки)

| Вкладка | Назначение | Основные RPC |
|---|---|---|
| **💬 Чат** | текущий голосовой чат: утка, push-to-talk, TTS, inline tool-use; сессии — в левом flyout | `chat.history/send/abort`, `sessions.list/create/reset/delete` |
| **▦ Пульт** | hub-плитки всех разделов конфигурации (см. §6) | — (навигация) |
| **⚡ Лента** | глобальный live-лог + cron-статусы + задачи + аппрувы + расходы | `cron.runs`, `tasks.list`, `exec.approval.list`, `usage.cost`, `logs.tail` |

### Command palette (🔍, поверх всего)
Иконка в топбаре любой вкладки → поиск-навигация и быстрые действия: «перейти к
<раздел>», «создать агента», «запустить cron <job>», «подключить интеграцию»,
«рестарт движка». Один пользователь-power-user (Denis) — это его основной
ускоритель.

### Карта навигации
```
BottomNav
├─ Чат (Screen.Chat)            ← бывший MainScreen
│   └─ left flyout: Сессии (sessions.*)
├─ Пульт (Screen.Hub)           ← новый
│   └─ 13 разделов → list+detail каждый (§6)
└─ Лента (Screen.Activity)      ← новый
Command palette (overlay, из топбара)
Settings — переезжает в Пульт как раздел (не отдельный top-level)
```

## 4. Дизайн-система

Переиспользуем `ui/theme/Theme.kt` (`DuqColors`) как есть. Новые
переиспользуемые компоненты (`ui/components/control/`):

- **StatusTile** — плитка hub: иконка + название раздела + 1 живая метрика
  («3 агента», «cron: 1 ошибка», «$2.40 сегодня») + цветной статус-индикатор.
- **EntityCard** — карточка в списке (сессия/агент/cron/канал): заголовок,
  подзаголовок, статус-чип, свайп-экшены (вкл/выкл, запустить, удалить).
- **EditSheet** — bottom sheet для быстрого создания/правки; сложная правка —
  отдельный detail-экран.
- **EmptyState** — иллюстрация (утка) + объяснение + primary CTA.
- **FloatingActivityLog** — расширение текущего `activeSteps`: плавающий
  glass-оверлей «что агент делает сейчас», доступен поверх любой вкладки.
- **SchemaForm** — генерируемая форма из `config.schema` (`uiHints`, типы,
  enum, min/max, `reloadKind`) — для раздела «Движок».
- **ConfirmBiometric** — гейт HITL перед рискованным действием.

Стекло — только на `EditSheet`, `FloatingActivityLog`, оверлеях. Списки/формы —
`surface`/`surfaceVariant` непрозрачные.

## 5. Экран Чат (изменения от текущего MainScreen)

Сохраняется всё: `DuqDuck` + push-to-talk, статус-текст, `MessagesList`,
inline `ToolStepsBlock`, баннер обновления, TTS, инбокс/дайджест шиты.
Меняется:
- Появляется **bottom navigation** (Чат активна).
- Топбар: «DUQ ▼» (открывает **flyout сессий** слева, не заглушку) + 🔍
  (command palette) + ⚙️ убирается (Settings теперь в Пульте).
- **Flyout сессий** (`sessions.list`): список чатов с превью
  (`sessions.preview`), создать (`sessions.create`), сбросить/удалить/сжать
  (`sessions.reset/delete/compact`), переключение активной сессии.

## 6. Разделы Пульта (каждый — на реальных RPC)

Для каждого: что показывает → RPC → list/detail → действия.

### 6.1 Агенты
- **Список:** `agents.list` (id, эффективная модель, runtime-метаданные).
- **Detail:** правка записи (`agents.update`), файлы воркспейса
  (`agents.files.list/get/set`), идентичность (`agent.identity.get`).
- **Действия:** создать (`agents.create`), удалить (`agents.delete`).

### 6.2 Модели
- `models.list` (`view:"configured"` для пикера, `view:"all"` — полный каталог).
- Дефолтные модели агентов: `agents.defaults.models`.
- Квоты провайдеров: `usage.status`.

### 6.3 Расписания (Cron)
- **Список + монитор:** `cron.list`, `cron.status`, история `cron.runs`
  (success/fail/running, last/next, лог ошибки, autorefresh).
- **Detail/правка:** `cron.get`, `cron.add`, `cron.update`, `cron.remove`.
- **Действия:** запустить вручную `cron.run` → poll `cron.runs` по `runId`;
  немедленный wake — `wake`.

### 6.4 Задачи
- `tasks.list` (ledger), `tasks.get` (детали), `tasks.cancel`. Группировка по
  статусу (не Kanban — на телефоне вертикальный список по статусу).

### 6.5 Навыки
- `skills.search`, `skills.detail`, `skills.status`, установка `skills.install`
  (+`skills.install.allow`), обновление `skills.update`. Загрузка —
  `skills.upload.begin/chunk/commit`.

### 6.6 Инструменты
- Каталог `tools.catalog`, эффективные `tools.effective`, команды
  `commands.list`. Ручной вызов `tools.invoke` (для отладки, под HITL).

### 6.7 Интеграции (каналы)
- **Список ~25 каналов:** `channels.status` (built-in + bundled, статус/аккаунт).
- **Подключение:** QR/web-логин `web.login.start` → `web.login.wait`.
- **Действия:** `channels.logout`; тест push для iOS-ноды `push.test`.

### 6.8 Память
- Статус/готовность: `doctor.memory.status` (`probe`/`deep` опц.).
- Dreaming: `doctor.memory.dreamDiary`, `backfillDreamDiary`, `resetDreamDiary`,
  `resetGroundedShortTerm`, `repairDreamingArtifacts`, `dedupeDreamDiary`.
- REM-превью: `doctor.memory.remHarness`. (Все принимают опц. `agentId`.)

### 6.9 Расходы
- `usage.cost` (диапазон дат; `agentId` или `agentScope:"all"`).
- Провайдеры: `usage.status`. Сессии: `sessions.usage`,
  `sessions.usage.timeseries`, `sessions.usage.logs`. Графики по дням/агентам/моделям.

### 6.10 Голос & TTS
- Talk: `talk.catalog`, `talk.config` (секреты — под `operator.talk.secrets`).
- TTS: `tts.status`, `tts.providers`, `tts.enable/disable`, `tts.setProvider`,
  `tts.convert`. Wake-word триггеры: `voicewake.get/set`.
- Сюда переезжают текущие слайдеры (sensitivity, silence timeout) из настроек.

### 6.11 Устройства (Nodes & pairing)
- Ноды: `node.list`, `node.describe`, `node.rename`, пэйринг `node.pair.*`.
- Парные устройства: `device.pair.list`, `device.pair.approve/reject/remove`,
  токены `device.token.rotate/revoke` (под HITL).
- Node-разрешения exec: `exec.approvals.node.get/set`. Capabilities телефона
  (камера/гео/экран/voice) — отображение + тумблеры разрешений.

### 6.12 Секреты
- `secrets.resolve` (по command/target), `secrets.reload` (re-resolve, swap при
  полном успехе). Просмотр/ввод — под `ConfirmBiometric`. SecretRefs живут в
  конфиге → правка через `config.patch` с биометрией.

### 6.13 Движок (Config & система)
- **Schema-driven форма:** `config.schema` (schema + `uiHints` + `reloadKind`),
  `config.schema.lookup` (drill-down по пути). Рендер через `SchemaForm`.
- Чтение/запись: `config.get`, `config.patch`, `config.set`, `config.apply`.
- Здоровье: `health`, `status`, `diagnostics.stability`. Логи: `logs.tail`.
- Обновление движка: `update.status`, `update.run` (под HITL, рестарт).
- Онбординг/мастер: `wizard.start/next/status/cancel`.

## 7. Экран Лента (Activity)

Единый монитор «здесь и сейчас», autorefresh:
- **Аппрувы (HITL, верх):** `exec.approval.list` + `plugin.approval.list` →
  карточки «разрешить/отклонить» (`exec.approval.resolve`,
  `plugin.approval.resolve`); ожидание `exec.approval.waitDecision`.
- **Cron:** последние/идущие прогоны (`cron.runs`, `cron.status`).
- **Задачи:** активные (`tasks.list`).
- **Расходы:** сводка за сегодня (`usage.cost`).
- **Лог:** хвост (`logs.tail`) + live agent-steps (текущий стрим).

## 8. Технические заметки

- **Навигация:** расширить `DuqApp` NavHost: добавить `Screen.Hub`,
  `Screen.Activity` + `Screen.Section.<name>`; `Scaffold` с `NavigationBar`.
- **RPC-слой:** в gateway-клиенте добавить типизированные вызовы
  `request(method, params)` для перечисленных методов; на каждый раздел —
  свой `*ViewModel` (AgentsViewModel, CronViewModel, ChannelsViewModel, …),
  Hilt. Переиспользовать существующий `request()`/`fetchHistory()` паттерн.
- **Scope:** многие методы требуют `operator.admin`/`operator.write`. Проверить
  что operator-токен телефона имеет нужный scope; иначе раздел read-only с
  явным баннером «нужен admin-scope».
- **Переиспользуем:** `ConversationViewModel`, `ChatStepReducer`,
  `MessageBubble`, `DuqDuck`, `NotificationInbox`, `DuqColors`.
- **Не ломаем:** пайринг, identity (Ed25519), node-клиент, автообновление.

## 9. Поэтапная реализация (после утверждения спека → отдельные планы)

1. **Каркас:** BottomNav + 3 вкладки, перенос текущего чата в `Screen.Chat`,
   пустые Hub/Activity, command palette-заглушка (навигация).
2. **Лента + Аппрувы** (HITL критичен) + cron-монитор.
3. **Высокочастотные разделы:** Агенты, Модели, Расписания, Интеграции.
4. **Остальные разделы:** Навыки, Инструменты, Память, Расходы, Голос,
   Устройства, Секреты, Движок (schema-form).
5. **Полировка:** flyout сессий, floating activity log, command palette
   действия, empty states, свайп-экшены, биометрия.

Каждая фаза = свой spec → план → реализация (push → CI → install по обычному циклу).

## 10. Вне рамок (явно)

- **Умный дом** — нет нативного API движка. Вернётся только при появлении
  MCP/tool-интеграции (тогда — отдельный раздел на реальных методах).
- **Wake word** — выкинут (решение Danny).
- Управление, для которого нет RPC, — не проектируется.
