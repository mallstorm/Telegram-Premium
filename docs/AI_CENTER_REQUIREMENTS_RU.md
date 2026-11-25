# Сверка реализации с исходным ТЗ

Источник: [20 страниц ТЗ](AI_CENTER_SPEC_RU.txt). Репозиторий `mallstorm/Telegram-Premium`, ветка `feat/folder-locks-dual`; Telegram Android 13.1.1 с двумя оболочками.

Таблица отражает реализованный код и автоматическую проверку. Она не заменяет эксплуатационную приёмку на телефоне с реальными Telegram-аккаунтами. Имена классов относятся к `patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium/aicenter/`, экран — `org/telegram/ui/TelePremiumAnalyzerActivity.java`.

| Требование ТЗ | Реализация | Проверка |
|---|---|---|
| §1,3. Центр вместо одного чата; единый индекс; переход между экранами и видимый охват | Экран источников, сводок, активности, авторов, медиа, транскрибатора, аналитика, отчётов, настроек; общая навигация и фильтр | Source/privacy wiring; Android CI; визуальная приёмка на устройстве |
| §1,12. Свободный запрос, отсутствие готовых плиток | Поле запроса, один переключатель формы ответа; пустой по умолчанию список пользовательских шаблонов | Source check; Android CI |
| §4. Все аккаунты, целиком/выборочно/исключён | `CenterIndex`, `CenterData.Scope`, экран источников; исключение очищает производные данные | `CenterIndexTest`, runtime-проверки удаления/изоляции |
| §4. Карточки аккаунтов и сведения об истории/сессии | Экран источников, данные `CenterSync` | Android CI; реальные сессии — устройство |
| §4. Тип, папка/метка, роль, статус, активность, текст, аккаунты: сочетание по И | `CenterData.SourceFilter`; массовое включение/исключение, именованные наборы | `CenterIndexTest` |
| §4. Период 1/7/30/90 дней, произвольный, с последнего просмотра | `CenterData.reviewFrom`, Scope, курсоры источников | `CenterIndexTest`, `CenterStatsTest` |
| §4. Виды сообщений и исключение служебных событий | `CenterSync`, Scope.kinds | `CenterIndexTest`, source/privacy wiring |
| §4. Своя очередь каждого аккаунта, бюджет, отмена/продолжение/FLOOD_WAIT | `CenterSync` и сохранённые курсоры; ограничение относится к проходу, не ко всей истории | Source check; Android CI; Telegram rate limits — устройство |
| §4. Общая группа читается один раз; дедупликация, свои авторы | Выбор аккаунта с доступом, канонические пространства каналов/групп, ключ чат+сообщение | `CenterIndexTest`, `CenterStatsTest` |
| §4,5. Недоступные аккаунты и неполное покрытие видны | Отдельный статус аккаунтов/источников и покрытия | `CenterStatsTest`, `CenterEvidenceTest` |
| §5. Все сообщения через map-reduce, без случайной выборки | `CenterReports.chunks`, `CenterAi`, кэш частей и повтор заданий | `CenterEvidenceTest` проверяет полный вход, в том числе длинные сообщения |
| §5. Группа, несколько групп, авторы, новое, сравнение, дайджест | Общий report pipeline; догрузка предыдущего периода; `CenterDigests` | `CenterStatsTest`, `CenterAuthorProfileTest`; расписание — Android CI/устройство |
| §5. Шапка, главное, темы, источники, авторы, цифры, неясности | Структура `CenterReports.Report`, проверяемые claims и настраиваемые блоки | `CenterEvidenceTest`, `CenterExportTest` |
| §5. Повторы, первая публикация, личные темы, слухи/неподтверждённое | `CenterTopics`, дедупликация содержания; статусы достоверности и исключение personal по умолчанию | `CenterStatsTest`, `CenterEvidenceTest`; качество классификации — реальные примеры |
| §6. Каждый факт: точная цитата, аккаунт, чат, сообщение | `CenterReports.validate`, изоляция локальных метаданных от ответа провайдера | `CenterEvidenceTest`, `CenterAuthorProfileTest`, `CenterMediaBundleTest` |
| §6. Раскрываемые источники, аватар/дата/превью, нет сырых msg-кодов | UI источников, `CenterReports.clean`, Unicode-безопасная цитата | `CenterEvidenceTest`, `CenterMediaBundleTest`; Android CI |
| §6. Переход в правильный аккаунт с подтверждением; возврат в отчёт | Native ChatActivity route и сохранение состояния/прокрутки | Android CI; фактический переход — устройство |
| §6. Контекст ±5, ссылка, авторы; удалённая цитата | `CenterReports.context`, шторка, локальное окно и явное обновление; сохранённые доказательства | `CenterExportTest`, `CenterEvidenceTest`; удаление в Telegram — устройство |
| §7. Все пять диаграмм, тихие группы, аномалии | `CenterStats`, `CenterRaster`, UI ActivityChart; сравнение с собственным прошлым уровнем | `CenterStatsTest`, `CenterLinksAndChartTest` |
| §7. Сортировка, 2–4 группы, детализация окна, сводка | UI фильтров графика, `CenterStats.window`, контекстный запуск отчёта | `CenterStatsTest`; Android CI/устройство |
| §7,13. Любая диаграмма → отчёт/PNG; точная таблица | `CenterChartReport`, chartModes, `CenterExport.chartRows`; таблицы в HTML/MD/PDF/CSV, в том числе без картинок | `CenterLinksAndChartTest`; отображение PDF — устройство |
| §8. Единый автор, поиск, сортировка, сравнение, собственные авторы | `CenterStats.Author`, пагинация, фильтры и сравнение | `CenterStatsTest`, `CenterAuthorProfileTest` |
| §8. Автоматический абзац, отличие от других в группе, доказательства | Локальный `CenterAuthorProfile`, реальные знаменатели и метаданные публикаций | `CenterAuthorProfileTest` |
| §8. Числа, темы, расписание, доли, лента, медиа, недельная динамика, новые темы | `CenterStats`, `CenterAuthorProfile`, публикации/галерея автора; новизна только при полном прошлом периоде | `CenterStatsTest`, `CenterAuthorProfileTest` |
| §8. Нет психологических выводов, профиль не сохраняется автоматически | Только публикационные метрики; ephemeral author-report, явный архив/экспорт | `CenterAuthorProfileTest`, source/privacy wiring |
| §9. Галерея, локальный OCR RU/EN, категории, смысловой поиск | Сетка с пагинацией, Tesseract, `CenterSearch`, `CenterTopics`; смысловой AI-поиск по явному действию | Android CI, закреплённые OCR-модели; точность — устройство |
| §9. Перцептивные дубликаты и первый в охвате | Хэш изображения, сортировка всех совпадений, переход в оригинал | `CenterStatsTest`; реальные изображения — устройство |
| §9. Изображение+подпись+обсуждение; источник каждого вывода | `CenterMediaBundle`, структурированный vision-ответ, точные страницы/кадры и цитаты обсуждения | `CenterMediaBundleTest` |
| §9. Полный просмотр/жесты, метаданные, вопрос о картинке | UI ZoomImage, карточка медиа, очередь описания | Android CI; жесты — устройство |
| §9. Нет распознавания личности, noforwards не загружается | Инструкции vision и проверки до загрузки/обработки/отправки | Source/privacy wiring; `CenterMediaBundleTest`; live Telegram — устройство |
| §9. Очередь, память/трафик, зашифрованные превью/кэш/срок, оценка отправки | `CenterTaskService`, `CenterStore`, `CenterMaintenance`; пакеты до пяти кадров; явный запуск | Android CI; ограничения памяти/сети — устройство |
| §10. Telegram, файл, открытое облако, меню сообщения | `CenterInputFiles`, SAF, native ChatActivity/Player hooks; закрытый cloud не допускается | `CenterInputFilesTest`, source/privacy wiring |
| §10. Прогресс, пауза, фон, уведомление, продолжение | Durable jobs, checkpoints, `CenterBackgroundJob` | Android CI; убийство процесса/Doze — устройство |
| §10. Язык, перевод, правка, поиск, перемотка | `CenterTranscript`, `CenterTranslation`, очередь translate; оригинал сохраняется отдельно | `CenterTranscriptTest`, `CenterTranslationTest`, Android CI |
| §10. Говорящие при поддержке движком, главы по теме | Поддерживаемый cloud diarization response; без идентификации, метки ограничены фрагментом; главы по цитатам | `CenterDiarizationTest`; provider/audio accuracy — устройство |
| §10. TXT/SRT/VTT и краткое содержание | `CenterFiles`, `CenterTranscript.subtitles`, report pipeline; запрещён пустой экспорт без таймкодов | `CenterTranscriptTest`, `CenterDiarizationTest`, `CenterTranslationTest` |
| §10. Локальная речь по умолчанию, облако отдельно и явно | Android on-device recognizer без сетевого fallback; отдельный cloudAsr запуск и согласие | Source/privacy wiring; для локальной речи нужны Android 13+ и установленный офлайн-движок |
| §10. Смена сцен, OCR, связь речи/кадров/таймкодов, пакет видео | `CenterMedia.frames`, перцептивная смена сцены, временные evidence, групповой отчёт | `CenterMediaBundleTest`, `CenterDiarizationTest`; реальные сцены/звук — устройство |
| §10. Wi-Fi/зарядка, потолок длительности, оценка времени/загрузки, кэш | JobScheduler, настройки и окно оценки; cache signatures включают движок/язык/диаризацию | Android CI; системные ограничения — устройство |
| §11. Все страницы PDF и доказательства страниц, DOCX/ODT/TXT/RTF | `CenterMedia.document`, PdfRenderer/PdfBox, `CenterDocuments`; общий бюджет вместо лимита 5 страниц | `CenterEvidenceTest`, `CenterDocumentsTest`; реальные файлы — устройство |
| §11. Ссылки: домен, реальный заголовок Telegram, частота публикаций, первый автор | `CenterLinks`; без веб-загрузки | `CenterLinksAndChartTest` |
| §11. Пересылки и цепочки, опросы/реакции | Оригинальный peer+post, известный промежуточный peer, хронология копий без недоказанной причинности; poll/reaction metadata | `CenterSync`, `CenterStats`; Android CI |
| §11. Архив/APK только статистика | `CenterDocuments.supported`, отказ до скачивания; постоянный статус unsupported без бесконечного повтора | `CenterDocumentsTest`, Android CI |
| §12. Свои шаблоны с охватом/расписанием, контекстные действия, очистка истории | UI/`CenterDigests`/`CenterStore`; шифрование и очистка по источнику | Source/privacy wiring; Android CI |
| §13. PDF/HTML/Markdown/TXT/CSV/SRT/VTT; конструктор/порядок | `CenterFiles`, `CenterPdf`, `CenterExport` | `CenterExportTest`, `CenterLinksAndChartTest`, `CenterTranscriptTest` |
| §13. Картинки с подписями/источниками, анонимизация, без картинок | Общие экспортные Options и Names; пиксели не изменяются, для этого предусмотрен режим без изображений | `CenterExportTest`; `CenterLinksAndChartTest` |
| §13. Зашифрованный архив; облако/поделиться только по кнопке | `CenterAssets`, `CenterStore`, явный export/share/cloud flow | `CenterExportTest`; Cloud/Vault runtime tests |
| §13. Дневной/недельный дайджест, архив и краткое уведомление | `CenterDigests`, `CenterDigestJob` | Android CI; JobScheduler может сдвигать запуск, проверяется на устройстве |
| §14. Зашифрованные account-bound записи и Keystore, лимит/срок/очистка | `CenterStore`, `CenterEnvelope`, миграция ключей, chunked blob reads | `CenterEnvelopeTest`; runtime SQLite regression |
| §14. Удаление сообщения, выход и исключение аккаунта удаляют производные данные | `CenterIndex`, `CenterMaintenance`, UserConfig logout hook; dependency-based cleanup | `CenterIndexTest`, source/privacy wiring |
| §14. Secret/noforwards/closed Vault не попадают в анализ | Проверки во всех входах и перед использованием; cloud lock отменяет и очищает производные данные | Source/privacy wiring; существующие Vault/Cloud runtime tests |
| §14. Только нужный материал, без телефона/сессии; согласия и local-only | `CenterAi`, фильтрация scope и owner, выборочные кадры+ближняя речь; все AI endpoints блокируются localOnly | `CenterIndexTest`, `CenterMediaBundleTest`, source/privacy wiring |
| §14. Только чтение, не read/send/join | Только read Telegram API в CenterSync; UI отдельно подтверждает share | Source/privacy wiring |
| §14. PIN/пароль/ключ/биометрия, блокировка в фоне, запрет скриншотов | Center lock, Vault-derived secret crypto, lifecycle hook, secure flag | Vault JDK/runtime tests; биометрия/lifecycle — устройство |
| §15. Модели по ролям, схема, кэш, расход/лимиты, retry и частичный результат | `CenterAi`, `CenterUsage`, `CenterTaskService`, content/model-aware caches | `CenterEvidenceTest`, `test_ai_center_usage.py`, Android CI |
| §16. Фоновое чтение/расчёты, миниатюры/пакеты, RU/EN, обе оболочки/темы | Центр общий для обеих оболочек; ресурсы RU/EN; фоновые очереди | 40 classic-shell guards, source/privacy, Java syntax, Android CI; визуальная/нагрузочная приёмка — устройство |
| §17. Пять этапов с APK на фичевой ветке | История контрольных сборок сохранена в [implementation](AI_CENTER_IMPLEMENTATION_RU.md) | Все пять исходных APK green; текущий итог проверяется отдельным run |
| Сбой со скриншота: SQLiteException в onConfigure | PRAGMA через rawQuery с выполнением ленивого cursor, проверкой значения и закрытием; без удаления базы | `test_ai_center_database_runtime.py`: реальная SQLite, воспроизведение старого сбоя, сохранность существующей записи |

## Условия окончательной проверки на устройстве

В этой среде нет Android-устройства, эмулятора и авторизованных тестовых аккаунтов. Поэтому следующие критерии остаются эксплуатационной приёмкой: два реальных аккаунта и закрытые/защищённые источники; переходы и возврат в отчёт; offline RU speech/OCR на пользовательских файлах; жесты/светлая/тёмная тема в обеих оболочках; биометрия; пауза/убийство процесса/Doze; экспортный PDF; память и батарея на больших индексах. Автоматические проверки и компиляция не объявляются заменой этих тестов.
