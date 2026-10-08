# CheckClearanceSystem

A Cheque Truncation System (CTS) for Indian clearing: outward capture at the
presenting bank, signed presentment to the clearing house, inward decisions at
the drawee bank, deemed approval, and hourly settlement on realisation, with a
double-entry ledger and an append-only audit trail.

Java 21 · Spring Boot 4.1 · Spring MVC + Thymeleaf · Spring Data JPA · Flyway · PostgreSQL 17 · Docker

## Try it

Sign in with any demo user. The password for all of them is `Cts@2026`.

| User | Role | Can |
|---|---|---|
| `maker` | MAKER | Capture cheques |
| `checker` | CHECKER | Approve or reject high-value items |
| `ops` | OPS | Present ready items, run settlement |
| `drawee` | DRAWEE | Act for the paying bank: confirm or return, register Positive Pay |
| `admin` | all | Everything, still bound by maker-checker |

### A walkthrough

1. **maker → Capture.** Key a cheque drawn on SBI Mumbai, attach any two
   images, and capture it:

   | Cheque no. | MICR code | Account | Amount | Payee |
   |---|---|---|---|---|
   | `000123` | `400002101` | `100201` | `25000` | anything |

   It goes straight to **READY**. Capture the same cheque again: it is
   rejected as a duplicate.
2. **maker → Capture** a high-value one: `000310` / `600211104` / `211044`,
   ₹`600000`, payee `Meera Textiles Pvt Ltd`, dated as registered for
   `000310` under **drawee → Positive Pay** (the day the database was seeded). It's held at
   **PENDING APPROVAL**, and the maker can't approve their own item.
3. **checker → Approvals →** open it and **Approve**.
4. **ops → Dashboard → Present ready items.** Each item is signed and sent to
   the clearing house, and the drawee bank runs its automatic checks at once.
   Both pass: the second one matches the drawer's Positive Pay registration.
5. **drawee → Inward →** open each item and **Confirm**, or **Return** it with
   a reason code. Anything left unanswered is deemed approved at 19:00 IST.
6. **ops → Settlement → Settle now.** Payees are credited. The batch shows each
   bank's net position, and those positions sum to zero.
7. **Ledger** shows every posting, and that debits equal credits.

Other seeded cases to try at capture (all on today's date unless noted):

| Cheque | MICR / account | Outcome |
|---|---|---|
| `000045` | `400002101` / `100201` | Returned **20**: drawer stopped payment |
| any serial, ₹20,000 | `560229103` / `229812` | Returned **01**: insufficient funds |
| any | `400002105` / `100777` | Returned **88**: account closed |
| any, ₹6,00,000 | `600211104` / `211044` (unregistered serial) | Returned **88**: Positive Pay required |
| any, dated 4+ months ago | any | Rejected at capture: stale |
| any, dated tomorrow | any | Rejected at capture: post-dated |

## How it works

```
 Presenting bank                Clearing house               Drawee bank
 ───────────────                ──────────────               ───────────
 capture ─ validate ─┐
  (MICR, stale,      │
   post-dated,       ├─ READY ─ sign ─────────────────────▶ verify signature
   duplicate)        │   (SHA256withRSA over item           account / status
 hold ≥ ₹1,00,000 ───┘    data + image hashes)              stop payment
 maker ≠ checker                                            Positive Pay
                                                            funds
                                                              │
                                     ◀── return (NPCI code) ──┤
                                                              ├── confirm (human)
                                                              └── deemed approval at expiry
 settle hourly (11:00–19:00 IST) ◀──────── confirmed ─────────┘
 credit payee · net positions per bank
```

| Rule | Where |
|---|---|
| MICR line: serial (6), MICR code (9 = city + bank + branch), account (6), txn code (2) | `cheque/MicrLine` |
| Cheques are valid for 3 months; post-dated cheques aren't payable early | `cheque/CaptureService` |
| One instrument can be live once: a partial unique index on MICR + account + serial | `V1__schema.sql` |
| Maker-checker on high-value items | `cheque/ApprovalService` |
| Item data and image hashes signed at presentment; the private key never stored | `security/SigningService`, `clearing/ItemData` |
| Drawee checks with NPCI return codes | `clearing/DraweeService` |
| Positive Pay: checked from ₹50,000, mandatory from ₹5,00,000 | `clearing/DraweeService` |
| Item expiry: phase 1 (19:00 IST cut-off) or phase 2 (T + 3h), set by `CTS_CLEARING_PHASE` | `clearing/ExpiryPolicy` |
| Settlement on realisation, net positions per bank | `clearing/SettlementService` |
| Double-entry, idempotent postings: a replay posts nothing | `ledger/LedgerService` |
| Audit trail that the database itself refuses to update or delete | `V1__schema.sql` trigger |
| Concurrent decisions on one item: optimistic locking, the second loses cleanly | `Cheque.version` |

Money is always integer paise. A JSON API (`/api/cheques`) sits over the same
services as the pages, ready for a mobile capture client.

## Run it

```bash
./mvnw spring-boot:run       # starts Postgres from compose.yaml (needs Docker)
docker compose --profile app up --build   # the whole stack in containers
```

Against an existing database, set `SPRING_DATASOURCE_URL`, `_USERNAME` and
`_PASSWORD`. Flyway creates everything in its own `cts` schema.

## Roadmap

The MVP keeps one process and simulates the clearing house and the drawee.
In production:

- **Messaging:** Kafka between presentment, the clearing house interface and
  inward processing, with an outbox so no item is lost or sent twice.
- **PKI:** an HSM through PKCS#11 for signing keys, with CA-issued certificates
  per bank instead of in-memory keys.
- **Images:** the three CTS-2010 images (front grey 100 DPI JPEG, front and
  back black & white 200 DPI TIFF G4), image quality assessment at capture,
  and object storage instead of the database.
- **MICR/OCR:** reading the code line from the image instead of keying it.
- **Integration:** NPCI's clearing house interface file formats, and the
  bank's core banking system (Finacle, Flexcube, T24) in place of the
  simulated drawee accounts.
- **Batch:** Spring Batch for end-of-day reconciliation against the clearing
  house's settlement report.
- **Mobile:** a Flutter capture app for branches, on the existing API.
