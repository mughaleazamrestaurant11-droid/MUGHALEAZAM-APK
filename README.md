# MUGHAL-E-AZAM — Waiter App (Android)

A phone is a **window onto the main PC**, never a second till.

The restaurant keeps exactly one copy of every bill, every table and every
dish: the one on the **main server PC**. This app only looks at it and sends
orders to it. It holds no database of its own, so if the phone is lost,
swapped or wiped, nothing of the restaurant goes with it — and nothing can be
edited behind the main PC's back.

---

## What it does

Four screens, and nothing else:

1. **Main server PC** — find the server on the shop's Wi-Fi, or type its
   address once (`192.168.10.50:8484`). It is remembered after that.
2. **Who are you?** — the same user name and password as the tills. The main
   server checks it; the phone never keeps the password.
3. **Tables** — the floor, live. Green is free, red already has an open bill.
   Sections can be filtered across the top.
4. **Order** — the menu at the bottom, what is already on the bill at the
   top. Tap to add, `+` / `−` to change, then **Save order** and optionally
   **Send to kitchen**.

That is the whole app, on purpose. There is no money, no reports, no
settings, no stock editing — the boss does those at the till.

---

## Getting the APK

You never install Gradle or Android Studio.

1. Put this folder on GitHub (see *Before you push* below).
2. Open the repository → **Actions** → **Build Android APK**.
3. GitHub builds it on its own computer. When the run turns green, open
   **Artifacts** at the bottom of the run page and download
   `waiter-app-apk`.
4. Copy the `.apk` to the phone and install it. You may need to allow
   "install from unknown sources" — the file is not from the Play Store.

To build again after a change: push, and it builds. To build by hand without
pushing: **Actions** → **Build Android APK** → **Run workflow**.

---

## Setting up a phone

1. Join the shop's Wi-Fi — the same network the main PC is on.
2. Open the app. Tap **Find on this Wi-Fi**. If the main PC is on, it appears;
   tap it, then **Continue**. (If it does not appear, type
   `192.168.10.50:8484` — use the main PC's own address.)
3. Sign in with the till user name and password.
4. Tap a table, tap the dishes, **Save order**.

Several phones can be signed in at once. They all see the same tables, and
the moment one of them takes an order, every other screen updates on its own
— no refresh button.

---

## The rules the app keeps

**The main PC stays the main PC.**
Every screen is a picture of the server's data and every save is a call to
the server. The phone never invents a bill, a price, a discount or a tax.

**If the main PC is off, it says so.**
A red strip appears: *"Main server PC is offline. Nothing is being saved."*
Saving is refused with that message instead of quietly pretending it worked.
There is no "saved, will sync later" — a queued order that appears twice, or
charges the wrong amount once the network returns, is worse than an order
that is never taken.

**A table can only have one bill.**
If the waiter taps a table that is already in use, the app joins the bill
that is already running there. It will not open a second one.

**Food the kitchen already has cannot quietly vanish.**
A line that has been sent to the kitchen shows it, and cannot be taken below
what was sent. Undoing food that is cooking is the till's call, not the
phone's.

**The shop's charges are copied, never re-invented.**
Service charge, tax and discount come from the server's own order and are
sent straight back unchanged. (Sending zeroes instead would silently take the
service charge and the tax off every bill in the restaurant — this was proved
as a real behaviour of the server, and the app is built around it.)

**The phone cannot change the main PC's settings.**
The one route that writes the server's port and window settings refuses
anybody who is not that computer.

---

## What is proved, and what is not

This machine has no Android SDK and no Gradle, so the app **has not been
compiled here**. That is exactly why the GitHub workflow exists. Two checks
stand in for a compile, and both pass:

| Check | What it proves |
|---|---|
| `tools/mobile_static_check.py` | Every screen, id, word, picture, colour and style the Kotlin references actually exists; braces balance; the manifest names real screens. It has its own self-test and was checked against a deliberately broken file. |
| `tools/mobile_contract_test.py` | **44 checks, 0 failed.** Every call the app makes, with the app's own payloads, against a *scratch copy* of the real database: discovery, login, tables, menu, the live socket, `order.create`, `order.save`, `order.kot`, the busy-table path, and that not one existing row changed. |

What these cannot catch: anything needing a real device — the screen layout on
a particular phone, the Wi-Fi scan, how a physical tap feels. Those are
settled on the first real build and the first phone, not on paper.

Re-run them any time:

```
C:\laragon\bin\python\python-3.10\python.exe tools\mobile_static_check.py
C:\laragon\bin\python\python-3.10\python.exe tools\mobile_contract_test.py
```

The contract test copies the database, switches every printer off in that
copy, and never touches the restaurant's real data. The database file's
fingerprint was identical before and after each run.

---

## Before you push to GitHub

This folder sits next to the **live restaurant**: the database, every bill,
the customer list, the staff passwords and the printer settings. Pushing those
would publish them to anyone who can see the repository.

`.gitignore` in the project root keeps all of it out of git on purpose — the
`dist/`, `data/`, `build/` and `build_logs/` folders, the database and the
machine settings, the built `.exe`, and Python/Android build litter. The code
still builds and the software still runs; no restaurant data leaves the PC.

Before your first push, have a look at `git status` and make sure nothing
under those folders is staged. If the repository is ever made public, change
the till passwords.

---

## Where things are

```
mobile/
  build.gradle.kts        the two plugins, nothing else
  settings.gradle.kts     where the libraries come from
  gradle.properties       plain settings, no local paths
  app/
    build.gradle.kts      Android 7.0 up, Java 17, OkHttp for the wire
    src/main/
      AndroidManifest.xml the four screens, internet only
      java/com/mughalezam/pos/mobile/
        PosApp.kt         the one shared connection
        api/PosApi.kt     HTTP actions + the live socket + Wi-Fi discovery
        api/Models.kt     the shapes the server sends, and how to read them
        ui/…              the four screens
        util/Prefs.kt     the server address and who signed in
      res/                layouts, words, colours, the gold seal

.github/workflows/build-apk.yml     builds the APK on every push
tools/mobile_contract_test.py       the 44 server-contract checks
tools/mobile_static_check.py        stands in for a compile
```

**Why classic views instead of Jetpack Compose:** Compose ties the compiler
version to the Kotlin version and breaks the build when they drift. These
screens are mostly lists, and the whole point of this project is that the
build keeps working every time you push.
