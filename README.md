# Compound for Android

A private finance app that reads your bank SMS on the phone, sorts spending into categories, tracks budgets, loans, EMIs, credit card dues, bills and subscriptions, and optionally syncs with your Compound server. No cloud service and no AI API: everything runs on the phone.

## Install

Google Play Protect blocks apps that read SMS when they're installed from a browser or file manager, so install from a computer over USB instead (ADB installs aren't blocked):

1. On the phone: **Settings → About phone**, tap **Build number** seven times, then **Settings → System → Developer options → USB debugging** on.
2. On a Mac: `brew install android-platform-tools`
3. Plug the phone in, accept the "Allow USB debugging?" prompt, then run:
   ```bash
   ./scripts/install.sh
   ```
4. Open Compound, tap **Allow SMS access**, and choose how many months of past messages to read.

Updates: run `./scripts/install.sh` again. Your data is kept, because every release is signed with the same key. You can switch USB debugging off between installs.

No cable? Use **Developer options → Wireless debugging → Pair device with pairing code**, then on the Mac run `adb pair <ip:port>` with the code, `adb connect <ip:port>`, and the script.

## Which messages it reads

Only messages from **authorised senders** (Settings → Which messages to read):

- bank sender IDs, matched if the sender contains them, e.g. `ADCB` matches `ADCBAlert`;
- phone numbers, matched on the last 9 digits, so `+971 50…` and `050…` both work;
- optionally, keywords: a message must also contain one of them, e.g. `debited, credited, purchase`.

Everything else is skipped and never stored. One-time passwords from authorised senders are recognised and their text is discarded.

## What it does

| Area | How |
|---|---|
| Transactions | Amount, currency, direction, merchant, card or account digits, balance after. Messages it can't read confidently go to **Review**. |
| Categories | Built-in UAE merchant list, plus your corrections: change a category once and that merchant is sorted automatically from then on. |
| Budgets | Monthly limit per category, suggestions from your 3-month average, alerts at 80% and 100%, pace warnings. |
| Loans & EMIs | Outstanding balance, months left, interest still to pay, payoff date, a "pay extra" calculator, and which loan to prepay first. EMI SMS mark the instalment paid. |
| Credit cards | Created automatically from statement and payment-due SMS: total and minimum due, due date, utilisation. Payment SMS clear the due. |
| Bills & subscriptions | Recurring charges detected from your SMS, yearly cost, price-rise and double-charge warnings, reminders before each due date. |
| Compound sync | Optional: shows net worth from your Compound server and sends it your income and spending. |

## Build

GitHub Actions builds, tests and publishes a signed APK on every push to `main`. To build locally, open the project in Android Studio (JDK 17), or run `gradle :app:assembleRelease` with Gradle 8.11.

The parsing, categorisation, recurring-charge and loan maths live in `app/src/main/java/com/compound/finance/core/` as plain Kotlin, with unit tests in `app/src/test/`. To support a new SMS format, add the real message to `SmsParserTest.kt` first, then adjust `SmsParser.kt` until it passes.

## Signing key

`keystore/compound-release.jks` is committed so GitHub can sign every build with the same key. It's encrypted; its password is stored only in the repository secret `COMPOUND_KEYSTORE_PASSWORD` (Settings → Secrets and variables → Actions). Without that secret, builds use a temporary key and no release is published, because an app signed with a temporary key can't be updated later.
