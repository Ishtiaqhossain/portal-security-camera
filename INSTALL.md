# Installing the Portal Security Camera app

A simple, step-by-step guide to getting the app onto your **Meta Portal**. You
don't need to build anything — grab the ready-made APK from the Releases page.

> The app is **sideloaded** (there's no Play Store on the Portal), so you install
> it over USB from a computer. It takes about 5 minutes.

---

## What you need

- A **Meta Portal** device (Portal, Portal+, Portal Go, or Portal TV) and its
  USB-C cable.
- A **computer** (Mac, Windows, or Linux) to copy the app across.
- A **signaling server** to connect to — either your own (see the main
  [README](README.md) / [`deploy/`](deploy/)) or one you've been given the
  `wss://…` address for. You can install the app first and add this later.

---

## Step 1 — Download the app

1. Go to the [**Releases**](../../releases/latest) page of this project.
2. Under **Assets**, download **`app-release.apk`**.
3. Save it somewhere easy to find, like your Desktop.

## Step 2 — Turn on Developer Mode on the Portal

1. On the Portal, open **Settings → System → Developer** (the exact path varies
   by model; search Settings for "Developer" if you don't see it).
2. Turn **Developer Mode** / **ADB** **on**.
3. Plug the Portal into your computer with the USB-C cable. If the Portal asks
   you to **Allow USB debugging**, tap **Allow** (tick "always allow" so it
   doesn't ask again).

## Step 3 — Install the APK

You need the `adb` tool, which comes with Android's
[Platform Tools](https://developer.android.com/tools/releases/platform-tools)
(download, unzip, and run the commands from inside that folder).

Open a terminal / command prompt and run:

```bash
# Confirm the computer can see the Portal (it should list one device):
adb devices

# Install the app (point this at wherever you saved the APK):
adb install -r app-release.apk
```

When it prints **`Success`**, the app is installed. You can unplug the cable.

> Already have an older version installed? The same `adb install -r` command
> upgrades it in place and keeps your settings.

## Step 4 — First launch & setup

1. On the Portal, open **Portal Security Camera** from the app list.
2. Grant **Camera** and **Microphone** permissions when asked.
3. You'll be asked to **set a 4-digit PIN** on first run. This PIN protects
   arming, settings, and viewer management — keep it private.
4. Open **Settings** and enter your **Signaling server** address
   (e.g. `wss://your-server.example.com`). Pick a **mode** and **video quality**,
   then **Save**.
   - **Drop In** — camera only turns on while someone is watching (most private).
   - **Active** — camera streams continuously, which enables **motion alerts**
     while you're away.
5. Back on the dashboard, tap **Arm**. The shield turns **green (Protected)**.
   It turns **red (Live)** whenever a viewer is connected.

## Step 5 — Add someone who can watch

1. On the Portal, open **Viewers → Show QR code**.
2. On the phone or laptop you want to watch from — **on the same Wi-Fi as the
   Portal** — scan that QR code.
3. That device now has its own private, revocable login and can watch from
   anywhere by opening `https://<your-server>/` in a browser.

You can revoke any viewer at any time from the **Viewers** screen on the Portal.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `adb devices` shows nothing | Re-seat the USB-C cable, make sure Developer Mode/ADB is on, and accept the **Allow USB debugging** prompt on the Portal. |
| `adb: command not found` | Install Android **Platform Tools** (link above) and run `adb` from inside that unzipped folder. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A different build is already installed. Remove it first: `adb uninstall com.meta.portal.security`, then install again. |
| App installs but won't connect | Double-check the **Signaling server** URL in Settings (it must start with `wss://`) and that the server is running. |
| Shield won't go green | The Portal needs network access to your signaling server; confirm the server address and that the Portal is online. |

---

## Want to build it yourself instead?

Developers can build the APK from source — see
[`portal-app/README.md`](portal-app/README.md). To set up the **server** side,
see the main [README](README.md) and [`deploy/`](deploy/).

> **A note on signing:** the released APK is signed for sideloading so it
> installs cleanly on your own device. It is **not** distributed through the
> Play Store. Per Portal policy and basic courtesy, **tell everyone in the
> household** that the camera can be viewed remotely.
