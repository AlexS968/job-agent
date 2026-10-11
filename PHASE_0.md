# Фаза 0 — контракт разработки Job Agent

Статус: фаза 0 завершена 2026-10-07. Пользователь подтвердил, что предварительная AWS-оценка подходит. Это завершение проектирования; код, deployment и миграция ещё не выполнены. LLM и поисковый API выбираются и оцениваются перед подключением.

Дата: 2026-10-07. Результат: подготовленная архитектура и требования для начала разработки. Код и AWS-ресурсы ещё не созданы. Выбор региона — рабочий baseline us-east-1 (N. Virginia); глобальное сравнение всех регионов не выполнено, поэтому он не объявляется самым дешёвым. Приоритет пользователя: стоимость и надёжность, расположение данных не важно.

## Региональное решение: Virginia и Frankfurt

2026-10-07 тарифы Aurora Standard / ServerlessV2 / OnDemand прочитаны из открытого AWS Pricing Calculator на одинаковых параметрах. Прежняя проблема доступа к региональному калькулятору разрешена; старое сообщение о невозможности сравнения больше не актуально.

| Тариф | Virginia (us-east-1) | Frankfurt (eu-central-1) |
| --- | --- | --- |
| Aurora V2, USD/ACU-hour | 0.12 | 0.14 |
| Storage, USD/GB-month | 0.10 | 0.119 |
| I/O, USD за миллион операций | 0.20 | 0.22 |
| Compute: 13 × 25/60 × 0.5 ACU | 0.325 | 0.379 |
| Storage при 10 GB | 1.00 | 1.19 |

Для этой нагрузки разница compute + storage около 0.24 USD/месяц. Virginia дешевле по проверенным тарифам БД; полного сравнения всех компонентов и всех регионов не выполнено. Fargate оценён по официальному примеру Virginia; его текущий Frankfurt unit rate здесь не проверен. Выбираем Virginia как рабочий регион для реализации с имеющейся оценкой. Разница в надёжности регионов не доказана; оба поддерживают необходимые сервисы. [Aurora regions](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Concepts.Aurora_Fea_Regions_DB-eng.Feature.ServerlessV2.html), [Fargate regions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate-Regions.html).

Исходные 54.08 USD на пользовательском экране относились к версии `Serverless` (не V2): 1 ACU × 0.07 × 730 часов = 51.10 плюс включённый Database Insights 2.28 и storage/I/O около 0.70. V2 Frankfurt при 1 ACU весь месяц: 102.20 USD compute. Калькулятор этой формы фиксирует 730 часов; его Total Monthly нельзя трактовать как расчёт 13 коротких batch-запусков. При auto-pause compute считается по фактическим ACU-hours; storage продолжает оплачиваться. Auto-pause необходимо подтвердить после deployment; рост длительности/ACU и maintenance увеличивают оценку.

Требования и технический проект фазы 0 готовы для начала кода; предварительная оценка сохранена. Платное развёртывание требует полного deployment estimate с выбранными LLM/search provider и budget alert. Документация не является разрешением создать платные ресурсы.

## Продукт и первая полезная версия

Агент самостоятельно ищет новые вакансии и работодателей, сохраняет результаты, нормализует и дедуплицирует вакансии, оценивает соответствие явным критериям и формирует shortlist со ссылками и аргументами. Gmail/LinkedIn — дополнительный источник. Перенос старого pipeline не заменяет самостоятельный поиск.

Первый MVP: самостоятельный поиск вне Gmail → PostgreSQL → оценка по критериям → reviewable shortlist. Следующий шаг — пользовательская обратная связь. Отклики, CV generation, semantic matching и аналитика идут по этапам MASTER_PLAN_RU.md. CV попросить только после готовности приватного S3.

## Требования, восстановленные из существующего процесса

Прочитаны текущий Master и история Job Search Pipeline. Основное направление: Java/Spring backend, product/in-house и international product/SaaS; Germany hiring надо подтверждать, а не выводить из слова remote. Consulting/client-project и посреднические предложения исключать по проверенным фактам. Не выдумывать зарплату, доступность страны, employment model и обязательные навыки. Неизвестные условия сохранять как unknown. Не отклонять Java-first роль только из-за отдельных дополнительных инструментов.

Персональные ограничения, история заявок и индивидуальные решения импортируются в приватную конфигурацию/БД и не публикуются в Git. Настоящие CV на этом шаге не нужны. Для публичных тестов используем вымышленные профили.

Master содержит пять вкладок: DE Arbeitgeber, International Remote, Aktionsliste DE, Aktionsliste INT, Gap Analysis DE. Это employer-centric данные с информацией о роли, remote, compensation, employment, status, priority, provenance и next action. Их нельзя импортировать как будто каждая строка — отдельная уникальная вакансия. Action lists становятся представлениями состояния, а не независимыми копиями master. Часовой пояс источника отличается от пользовательского: при импорте сохранять исходные значения дат и явно нормализовать их, не менять существующий Sheet.

## Источники самостоятельного поиска

1. Greenhouse Job Board API: чтение опубликованных вакансий работодателя и полного описания через публичные GET endpoints. [Документация](https://docs.greenhouse.io/job-board.html).
2. Lever Postings API: вакансии, location, workplace type, description и optional salary. [Документация](https://github.com/lever/postings-api).
3. DiscoveryAdapter: широкий поиск новых работодателей и карьерных страниц по критериям, а не только обход фиксированного списка. В интерфейсе SearchGateway запрос, бюджет запросов и список URL; провайдер поиска выбирается до подключения платного API. Найденные URL проходят проверку официального работодателя и поддерживаемого board/API. Greenhouse/Lever сами по себе не являются глобальными поисковиками.

Первый адаптер — Greenhouse, второй — Lever. Публичная доступность API не отменяет проверку условий использования, rate limits и retry/backoff при реализации. Реальные live endpoint smoke tests выполняются в PR адаптера; сейчас проверена документация, адаптеры ещё не написаны.

## Предлагаемая AWS-схема

EventBridge Scheduler → ECS Fargate RunTask → контейнер Java 21/Spring Boot → private Aurora PostgreSQL Serverless v2. Один writer, Aurora Standard, минимум 0 ACU и auto-pause 5 минут, начальный maximum 2 ACU. Версия движка должна поддерживать auto-pause и будущий pgvector. Docker PostgreSQL локально, Flyway и обычный JDBC в обоих окружениях.

Fargate task в public subnet с временным public IPv4, без inbound правил; исходящие HTTPS к источникам/LLM и private JDBC к БД. Aurora — без public endpoint, порт доступен только от task security group. NAT Gateway, постоянный load balancer, RDS Proxy и постоянно работающий ECS service не нужны для batch MVP. Образ в ECR. [Fargate networking](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/networking-outbound.html).

Fargate выбран для обычного Spring Boot контейнера и длительного прохода discovery, чтобы не привязывать полный run к лимиту Lambda 15 минут. Lambda остаётся возможной для коротких последующих API/служебных операций. Data API на старте не нужен: сохраняем стандартный JDBC/Flyway. Обычный RDS — альтернативный вариант при постоянной нагрузке; EC2 PostgreSQL дешевизной не компенсирует самостоятельное обслуживание БД для первого MVP.

S3 private, block public access, encryption, versioning и lifecycle. Ключи: documents/, cv-generated/, reports/, exports/; без имён людей в object keys. Secrets Manager для DB и OAuth; Parameter Store Standard SecureString можно использовать для статических provider credentials после выбора ключа KMS. IAM task role ограничена конкретными объектами и секретами. CloudWatch structured logs без тел писем/секретов, retention 14 дней, alarm на failed run. Scheduler timezone Europe/Berlin, Пн/Ср/Пт; точный час выбираем до включения.

Надёжность MVP: управляемое хранилище, транзакции, unique source IDs, resumable runs, ограниченные повторы, отчёт об ошибках и backup/restore drill. Один writer — без мгновенного failover на второй compute instance; для этого batch-продукта допустимо восстановление/повтор запуска. До cutover доказать восстановление БД из backup. Предлагаемые цели: backup retention 7 дней, восстановление в пределах 4 часов; это цели для проверки, не уже достигнутые гарантии.

## Предварительный расчёт

Расчёт для us-east-1, USD до налогов, без Free Tier/credits. Допущения: 13 запусков по 20 минут, 1 vCPU/2 GiB Fargate; БД в среднем 0.5 ACU во время run и ещё 5 минут до pause; 10 GiB DB storage как консервативная база; 3 Secrets Manager secrets. Это оценка, не обещание счёта. Реальная нагрузка и дополнительное обслуживание БД могут увеличить расходы.

| Компонент | Расчёт | USD/месяц |
| --- | --- | --- |
| Fargate x86 | 4.333 часа × (0.0404784 CPU + 2 × 0.004446 memory) | 0.21 |
| Aurora compute | 13 × 25/60 часов × 0.5 ACU × 0.12 | 0.33 |
| Aurora storage | 10 GiB × 0.10, оценка по опубликованному примеру Standard | 1.00 |
| 3 secrets | 3 × 0.40 | 1.20 |
| Дополнительные расходы | Резерв на S3/ECR, I/O, логи, IPv4, backups и requests; не тариф | 1–3 |
| Итого AWS | До LLM, поискового API, налогов и обменного курса | около 3.7–5.7 |

Источники тарифных примеров: [Aurora](https://aws.amazon.com/rds/aurora/pricing/), [Fargate](https://aws.amazon.com/fargate/pricing/), [Secrets Manager](https://aws.amazon.com/secrets-manager/pricing/). Для другой длительности и ACU расчёт пересчитывается. При 1 ACU вместо 0.5 соответствующая строка удвоится. Не оставлять соединения открытыми после batch: pause зависит от отсутствия соединений. [Правила auto-pause](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-serverless-v2-auto-pause.html).

Предлагаемый сигнал AWS Budget — 10 USD/месяц: это alert, не жёсткий spending cap и не согласованный пользователем предел. LLM и поиск считаются отдельно после выбора провайдеров и лимитов запросов. Первое платное развёртывание требует согласованной полной оценки; региональные и service-specific rates проверяются в конфигурации перед deployment.

## Схема и первый код

Первый technical increment: agent_runs, source_items, employers, vacancies, vacancy_sources; processed_messages и external_actions вводим до Gmail. Следом evaluation_criteria, vacancy_evaluations, review_feedback. У source unique(source, source_id); у action unique idempotency_key. Source payload JSONB, URL, fetched_at, extraction_version. Domain write и pending action — одна transaction. LLM/HTTP вне DB transaction. Gmail finalize только после commit, retry action не повторяет domain ingestion.

Первый implementation PR: Maven/Java 21/Spring Boot, Docker Compose PostgreSQL, Flyway, run/source/vacancy persistence, CI и synthetic fixture. RunService управляет прогоном; SourceAdapter возвращает payload; VacancyNormalizationService формирует контракт; VacancyRepository сохраняет; EvaluationGateway появляется в следующем инкременте. Контроллер не обязателен для batch запуска. Shortlist/review API вводим с пользовательским обзором, защищаем перед доступом к персональным данным.

Готовность первого PR: clean start, migrations, один synthetic run; повтор входа не создаёт дубли; rollback не оставляет частичного состояния. Второй PR — первый live source; третий — второй source/dedup; затем широкий discovery, LLM/shortlist и cloud deployment. Нет claims о найденных вакансиях, реализованном агенте или AWS deployment до соответствующих проверок.

## Миграция и карьерные доказательства

До cutover текущий Sheet — единственный master старого pipeline. Новые тестовые данные не управляют Gmail и не создают вторую действующую копию старого состояния. Перед cutover: snapshot, import с provenance, сверка работодателей/вакансий/status/actions, final delta при остановленном старом writer, cloud rehearsal, только затем switch. Сохраняем rollback export и не откатываем внешние действия слепым восстановлением snapshot.

Фаза 0 дала документированные требования и проектное решение; программа ещё не реализована. После merged PR проверять закрытый шаг, evidence и материал для CV/LinkedIn/interview; фиксировать в Career Board, Narrative и CAREER_EVIDENCE.md. Регион и бюджетные цифры здесь не означают создание платных ресурсов.
