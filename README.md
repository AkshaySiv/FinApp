# Family Wealth

A private, local-only finance dashboard for you and your spouse: net worth, assets, Indian / US / UAE stocks, loans and EMIs, and goals. Every amount can be shown in AED, INR or USD.

## Start it on your Mac

1. Unzip the folder somewhere permanent, e.g. `~/Applications/family-wealth`.
2. Open **Terminal** in that folder and run:
   ```bash
   chmod +x run.sh "Family Wealth.command"
   ./run.sh
   ```
   The first run creates a Python virtual environment (about 30 seconds). After that, your browser opens at **http://127.0.0.1:8765**.
3. Next time, just double-click **`Family Wealth.command`**. If macOS blocks it the first time, right-click it, choose **Open**, then confirm.

You need Python 3.10 or newer. If `python3` is missing, run `xcode-select --install` or `brew install python`.

To stop the app, press `Ctrl+C` in the Terminal window. Stopping it also locks the vault.

## What's inside

| Section | What it does |
|---|---|
| **Dashboard** | Net worth, assets, liabilities, monthly EMI, stock P&L, net-worth trend, allocation by asset class, currency exposure, family split, upcoming EMIs and goal progress |
| **Family** | Net worth per member, plus a link to each member's own dashboard |
| **Assets** | Cash, FDs, mutual funds, gold, property, EPF/PPF/NPS, bonds, crypto, insurance, vehicles; each one valued automatically (see below) |
| **Indian / US / UAE Stocks** | Holdings (NSE/BSE, NASDAQ/NYSE/ETF, DFM/ADX), average cost, LTP, day change, P&L, weights, split by owner, and price refresh |
| **Loans & EMI** | EMI and outstanding balance from the amortisation schedule, progress, next due date, interest left to pay, a full schedule with chart, and a what-if EMI calculator |
| **Goals** | Target, date, progress (savings plus any linked assets), and the monthly saving needed at your expected return |
| **Settings** | FX rates (manual or live), auto-lock, password change, encrypted backup, CSV export |

**Switches in the top bar**
- **Currency (AED / INR / USD):** shows everything in that currency. Tables still show each item's own currency too.
- **Family / member:** shows the whole family or one person on every page. A *Joint* item is split equally between members.

## Automatic valuation

Every asset has a **"How is it valued?"** setting. It is filled in for you based on the asset class.

| Method | Use it for | How the value is worked out |
|---|---|---|
| **Live market price** | Gold (`XAU`), silver (`XAG`), crypto (`BTC-USD`, `ETH-USD`), ETFs, any Yahoo Finance ticker | quantity × live price. For gold: × karat ÷ 24 × (1 + premium %) |
| **Mutual fund NAV (India)** | Any Indian mutual fund | units × the latest official AMFI NAV. Search by fund name in the form |
| **Interest accrual** | FD, RD, EPF, PPF, bonds | principal grown by the rate and compounding up to today, stopping at maturity |
| **Yearly growth / depreciation** | Property (+5–8%), vehicles (−15%) | reference value × (1 + rate) for each year since the reference date |
| **Manual** | Bank balances, insurance, anything else | the value you type |

- **When prices update:** live prices, NAVs and FX rates refresh when you unlock, every 15 minutes while unlocked (change this in Settings), and whenever you click **↻ Prices** in the top bar. Stocks in all three markets update the same way.
- **Check before saving:** the **Check** button in the form confirms a symbol or scheme code works.
- **When a fetch fails:** the item shows a ⚠ marker and keeps its last known value.
- **Gold price:** it is international spot from COMEX futures, per gram. Indian retail is usually about 6–10% higher (import duty plus GST); enter that as *Premium %* if you want retail value.
- **Existing assets:** items created before this update are set to *Manual*. Edit each one and choose a method.

## Insights and the AI advisor

- **Insights** scores every stock on its own market behaviour over the past year (return against the Nifty 50 or S&P 500, 200-day trend, distance from the 52-week high), not on your buy price. It also reports emergency cover, EMI burden, savings rate, concentration and goal funding. Price history comes from Yahoo Finance, fetched slowly once a day; UAE stocks have no index comparison.
- **Loan check** (AI Advisor page) shows what a new loan does to your EMIs, monthly surplus and emergency cover, and compares borrowing with using your own funds.
- **AI Advisor** answers questions from your real numbers. It runs on your Mac through [Ollama](https://ollama.com); nothing is sent to the internet. The app does the calculations and the model explains them.
- **Set-up:** enter income and expenses under Settings → Cash flow. For the chat: `brew install ollama`, `ollama serve`, then `ollama pull llama3.1:8b` (use `llama3.2:3b` on 8 GB Macs, a larger model on 32 GB+).
- The scores and verdicts are rule-based analysis, not regulated financial advice.

## Broker sync (Zerodha & Fyers)

Open **Broker Sync** in the sidebar. Stocks go to **Indian Stocks** and mutual funds to **Assets** (tagged ⇆). The sync runs when you unlock the app and then every 30 minutes; you can change the interval in Settings.

| | Zerodha | Fyers |
|---|---|---|
| App to create | developers.kite.trade → **Personal** app (free) | myapi.fyers.in → Create App (free) |
| Redirect URL to register | `http://127.0.0.1:8765/connect/zerodha` | `http://127.0.0.1:8765/connect/fyers` |
| What syncs | Demat stocks and ETFs; Coin mutual funds (if Zerodha's free app allows it) | Stocks and ETFs; mutual funds held in demat |
| Daily login? | Only to pick up buys and sells. Sessions end around 6 AM IST; prices keep updating from market data without it | **No**, if you save your PIN: the session renews itself for about 15 days |

- **Mutual fund prices:** each fund is matched to its AMFI scheme code, so its NAV keeps updating daily even after the broker session ends.
- **Sold holdings:** removed from the app on the next sync.
- **Duplicates:** stocks you added by hand earlier aren't touched. Delete any that now duplicate a synced holding; they are flagged ⚠.
- **Family accounts:** add one connection per account, e.g. your wife's Zerodha, and set *Whose account?*. Each needs its own developer app, created while logged in as that person.
- **Your keys:** the API secret, access tokens and PIN are stored only inside the encrypted vault. They are never sent to the browser or anywhere except the broker's own servers.
- **Port:** if you run the app on a different port, use that port in the redirect URLs too.

## How your data is protected

- **Encrypted at rest.** Everything is stored in one file, `vault.enc`, encrypted with **AES-256-GCM**. The key comes from your master password via **scrypt**, which is designed to be slow to brute-force. Your password is never stored.
- **No password recovery.** If you forget the master password, the data cannot be decrypted. Keep it in your password manager.
- **Local only.** The server listens on `127.0.0.1` only, so other devices on your Wi-Fi can't reach it. It also rejects requests from other hostnames and origins, which blocks DNS-rebinding attacks.
- **Auto-lock.** After 10 idle minutes (you can change this) the key is wiped from memory and you must unlock again. Three failed unlock attempts in a row trigger an increasing delay.
- **File permissions.** The vault and backups are readable only by your macOS user (`chmod 600` for files, `700` for the folder).
- **Backups.** An encrypted backup is made automatically at most once an hour, and the last 30 are kept. You can also download one in Settings. These files are safe to keep on iCloud Drive or a USB stick.
- **Network use.** The app only connects to Yahoo Finance, TradingView's scanner feed (Abu Dhabi stocks), xaus.com, gold-api.com, CoinGecko (prices), api.mfapi.in / amfiindia.com (NAVs), open.er-api.com (FX), and — if you connect them — Zerodha and Fyers. It sends only ticker symbols, scheme codes and currency codes — never your quantities, values or names. Charts and fonts are bundled, so there are no CDN calls. To turn price updates off, set auto-update to 0 in Settings.

**Data location:** `~/Library/Application Support/FamilyWealth/`

**Restore a backup:** stop the app, replace `vault.enc` with the backup file (rename it to `vault.enc`), then start the app again.

For extra protection, turn on FileVault in System Settings → Privacy & Security.

## Tips

- **Stock prices:** Indian tickers are fetched as `SYMBOL.NS` (or `.BO` for BSE), and UAE tickers as `SYMBOL.AE`. If a holding shows ⚠, set **Yahoo symbol override** on it.
- **Loans:** enter the principal, rate, tenure and **first EMI date**. If your bank statement shows a different balance (prepayments, rate changes), enter it under **Actual outstanding**.
- **Credit cards:** choose type *Credit card*, set principal to 0, and enter the due amount as both *Actual outstanding* and *EMI*.
- **Goals:** in any asset or holding, set **Earmarked for goal** so its value counts toward that goal.
- **Net-worth trend:** one snapshot is saved per day whenever you open or edit the app, so the chart fills in over time.

## Options

```bash
FW_PORT=9000 ./run.sh                       # use a different port
FW_DATA_DIR=~/Documents/fw-vault ./run.sh    # store the vault somewhere else
```

## Project layout

```
backend/app.py       API server, security checks, background refresh
backend/pricing.py   price sources: Yahoo Finance, AMFI NAV, FX
backend/insights.py  stock health, household findings, loan check, AI context
backend/ai.py        local Ollama client (loopback only)
backend/brokers.py   Zerodha Kite Connect + Fyers API v3 login and holdings sync
backend/vault.py     encryption (AES-256-GCM + scrypt), atomic saves, backups
backend/finance.py   currency conversion, loan amortisation, goal maths, summaries
static/              dashboard UI (vanilla JS + bundled Chart.js)
```
