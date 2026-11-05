# Air-gapped Docker deployment

Bu doküman, projeyi internet bağlantısı olmayan (air-gapped) bir ortamda `docker-compose.yml` ile tek seferde kaldırmak için gereken adımları açıklar. Üç servis çalışır: `postgres` (veritabanı), `onlyoffice` (ONLYOFFICE Document Server — belge düzenleme), `app` (Spring Boot backend + Yaver UI + REST API'ler).

## Mimari özeti

```
tarayıcı ──▶ app (8081)  ──▶ postgres (5432, sadece loopback)
                │
                └────────────▶ onlyoffice (8082, sadece loopback)
```

- `app` dosya yükleme/indirme, belge üretimi ve ONLYOFFICE entegrasyonunu tek container'dan sağlar.
- `onlyoffice` tarayıcıdaki belge düzenleyiciyi render eder; `app` ile JWT imzalı isteklerle haberleşir.
- AI/Chat özelliği (bkz. aşağıdaki "Sınırlamalar") **isteğe bağlıdır** — `ANTHROPIC_API_KEY` verilmezse devre dışı kalır, dosya yönetimi ve ONLYOFFICE düzenleme bundan etkilenmez.

## 1. İnternet bağlantılı makinede hazırlık

Bu adım, air-gapped ortama taşınacak Docker imajlarını üretir. İnternet erişimi olan bir makinede (geliştirme makinesi, build sunucusu) çalıştırın. Docker Compose, derlemeden önce de ortam değişkenlerini doğrular; `.env` hazırlığı bu nedenle `build` komutundan önce yapılmalıdır.

```bash
cd office-manipulator
test -f .env || cp .env.example .env
openssl rand -hex 32
nano .env
```

Üretilen anahtarı `.env` dosyasına `OFFICE_JWT_SECRET=...` olarak ekleyin. Yeni kurulumda `DB_PASSWORD=changeme` yerine kendi parolanızı belirleyin; mevcut veritabanı kullanılıyorsa mevcut parolayı koruyun. Var olan ayarları ve API anahtarlarını silmeyin. JWT anahtarını her derlemede değiştirmeyin. `.office.local.env` ayrı deneme ortamına aittir ve bu Compose dosyası tarafından okunmaz.

```bash
docker compose config --quiet
docker compose pull postgres onlyoffice
docker compose build
```

`pull` komutu PostgreSQL ve ONLYOFFICE imajlarını indirir. `build` komutu ise `app` imajını yerelde derler (Maven + Node bağımlılıkları imaja gömülür, çalışma zamanında internet gerekmez).

İmajları dışa aktarın:

```bash
docker save office-manipulator-app -o app.tar
docker save postgres:16-alpine -o postgres.tar
docker save onlyoffice/documentserver:10.0.0 -o onlyoffice.tar
```

(`office-manipulator-app`, projenin klasör adından türetilen varsayılan imaj adıdır — `docker compose config --images` ile doğrulayabilirsiniz.)

`app.tar`, `postgres.tar`, `onlyoffice.tar` dosyalarını, proje kaynak kodunun tamamıyla (veya en azından `docker-compose.yml`, `.env.example` dosyalarıyla) birlikte air-gapped ortama taşıyın (harici disk, USB vb.).

## 2. Air-gapped makinede kurulum

```bash
docker load -i app.tar
docker load -i postgres.tar
docker load -i onlyoffice.tar
```

`.env` dosyasını `.env.example`'dan oluşturun ve en az `DB_PASSWORD`, `OFFICE_JWT_SECRET` değerlerini doldurun:

```bash
test -f .env || cp .env.example .env
openssl rand -hex 32
nano .env
```

Üretilen anahtarı `OFFICE_JWT_SECRET` alanına, veritabanı parolasını `DB_PASSWORD` alanına yazın. Mevcut bir kurulumda bu değerleri koruyun.

`ANTHROPIC_API_KEY` alanını **boş bırakın** — air-gapped ortamda internet olmadığı için bu özellik zaten çalışmaz; boş bırakmak uygulamanın düzgün başlamasını sağlar (bkz. Sınırlamalar).

`docker-compose.yml`'deki `app` servisinin `build: .` satırı, imaj zaten yüklendiği için devreye girmez; doğrudan `docker compose up` ile önceden yüklenen imaj kullanılır. İsterseniz `build: .` satırını `image: office-manipulator-app:latest` ile değiştirip build context'ini tamamen kaldırabilirsiniz.

```bash
docker compose config --quiet
docker compose up -d --no-build --pull never
docker compose ps
```

Üç servisin de `healthy`/`running` olduğunu doğrulayın. Uygulama **http://localhost:8081**, ONLYOFFICE **http://localhost:8082** adresinde yayınlanır (varsayılan olarak sadece `127.0.0.1`'e bağlıdır).

## 3. Ortam değişkenleri (.env)

| Değişken | Açıklama | Air-gapped varsayılan |
| --- | --- | --- |
| `DB_PASSWORD` | PostgreSQL parolası | zorunlu, kendiniz belirleyin |
| `OFFICE_JWT_SECRET` | ONLYOFFICE ↔ app arası imzalama anahtarı | zorunlu, kendiniz belirleyin (örn. `openssl rand -hex 32`) |
| `ANTHROPIC_API_KEY` | Claude API anahtarı | **boş** — AI/Chat uçları kapalı kalır |
| `ANTHROPIC_BASE_URL` | Anthropic Messages API uç noktası | **boş** — resmî `https://api.anthropic.com`; yalnızca kendi barındırdığınız/proxy bir uç nokta kullanacaksanız doldurun |
| `STORAGE_BASE_PATH` | Üretilen/yüklenen dosyaların diskteki yolu | `/data/files` (container içi, `filedata` volume'üne bağlı) |
| `FILE_TTL_HOURS` | Üretilen dosyaların saklama süresi | `72` |
| `BUDGET_LIMIT_USD` | AI çağrıları için bütçe tavanı (AI kapalıyken etkisiz) | `20.00` |
| `GEMINI_API_KEY`, `GEMINI_ENABLED` | Opsiyonel görsel üretim (AI gibi internet gerektirir) | `GEMINI_ENABLED=false` |

## 4. API özeti

Tüm uçlar `app` servisi (varsayılan `http://localhost:8081`) üzerinden sunulur.

| Yöntem & yol | Açıklama | AI gerektirir mi? |
| --- | --- | --- |
| `POST /api/chat` | Doğal dil talimatından belge üretim işi (job) başlatır | Evet — key yoksa `503` |
| `GET /api/jobs` | İş geçmişi | Hayır |
| `GET /api/jobs/{id}` | İş durumu/detayı | Hayır |
| `GET /api/jobs/{id}/events` | İş durumu için SSE akışı | Hayır |
| `POST /api/jobs/{id}/edit` | Mevcut belgeyi talimatla düzenle | Evet |
| `POST /api/jobs/{id}/edit-fragment` | Belge içinde küçük bir parçayı düzenle | Evet |
| `POST /api/jobs/{id}/cancel` | İşi iptal et | Hayır |
| `GET /api/jobs/{id}/plan`, `POST /api/jobs/{id}/plan/confirm` | Üretim planını görüntüle/onayla | Evet |
| `POST /api/jobs/{id}/convert` | Üretilen belgeyi başka formata çevir | Hayır |
| `GET /api/budget` | Bütçe/kullanım durumu | Hayır |
| `GET /api/files/{jobId}/download` | Üretilen/yüklenen dosyayı indir | Hayır |
| `GET /api/files/{jobId}/preview` | Belge önizlemesi | Hayır |
| `POST /api/office/import` | Mevcut bir belgeyi (DOCX/XLSX/PPTX/CSV/PDF) ONLYOFFICE ile düzenlemek için içe aktar | Hayır |
| `GET /api/office/capabilities` | ONLYOFFICE entegrasyonunun açık olup olmadığı ve desteklenen formatlar | Hayır |
| `POST /api/office/jobs/{id}/session` | Belge için düzenleme oturumu aç | Hayır |
| `GET /api/office/sessions/{id}` | Oturum durumu | Hayır |
| `POST /api/office/sessions/{id}/save` | Düzenlenen belgeyi kaydet | Hayır |
| `GET /api/office/sessions/{id}/document` | ONLYOFFICE'in belgeyi çektiği imzalı uç (dahili) | Hayır |
| `POST /api/office/sessions/{id}/callback` | ONLYOFFICE'ten kaydetme bildirimi (dahili) | Hayır |
| `POST /api/office/sessions/{id}/agent` | Yaver Agent eklentisi: seçili metni AI ile öner | Evet — key yoksa `503` |
| `GET /office-plugin/config.json` | ONLYOFFICE eklenti yapılandırması | Hayır |

`EDITABLE_FORMATS` (desteklenen formatlar: DOCX, PPTX, XLSX, CSV, PDF) `OfficeService` içinde tanımlıdır.

## 5. Sınırlamalar

- **AI/Chat özelliği internet gerektirir.** `ANTHROPIC_API_KEY` (ve opsiyonel `GEMINI_API_KEY`) Anthropic/Google'ın bulut API'lerine HTTPS çağrısı yapar. Air-gapped bir ortamda bu anahtarlar tanımlanmamalı; tanımlanmazsa uygulama normal başlar, sadece `/api/chat` ve Yaver Agent eklentisinin "öneri" özelliği `503 Service Unavailable` döner. Dosya yükleme/indirme, format dönüştürme ve ONLYOFFICE ile belge düzenleme bundan etkilenmez ve tamamen offline çalışır.
- **Tarayıcı tarafı yoktur.** Bu sürüm yalnızca API sunar; ONLYOFFICE eklentisi ve web arayüzü dosyaları kaldırılmıştır, dış adrese istek yapılmaz.
- **Docker base imajları önceden indirilmelidir.** `postgres:16-alpine`, `onlyoffice/documentserver:10.0.0` ve derlenen `app` imajı, air-gapped ortama taşınmadan önce internetli bir makinede çekilip `docker save`/`docker load` ile aktarılmalıdır (bkz. Bölüm 1-2).
- `scripts/office-native`, `compose.office.yml`, `compose.office-host.yml` ayrı bir **geliştirme/deneme (trial)** akışına aittir (bkz. [onlyoffice-setup.md](onlyoffice-setup.md)) ve `ANTHROPIC_API_KEY`'i zorunlu kılar; air-gapped production teslimatı için kullanılmaz — bu teslimat için tek kaynak kök dizindeki `docker-compose.yml`'dir.

## 6. Doğrulama

```bash
docker compose ps                                   # üç servis de healthy olmalı
curl -s http://localhost:8081/api/office/capabilities
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/office-plugin/plugins.js   # 200, yerelden
curl -s -X POST http://localhost:8081/api/chat -H 'Content-Type: application/json' -d '{"message":"test"}'  # ANTHROPIC_API_KEY boşsa 503
```
