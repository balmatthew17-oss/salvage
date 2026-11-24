# Salvage

Android app that watches eBay UK for faulty PS5, Xbox Series X and Xbox Series S consoles under your own price limits, so you can buy, repair and flip them.

- Uses eBay's official Browse API: "For parts or not working" listings in the Video Game Consoles category, located in the UK
- Separate max price for each console (postage included)
- Reads the title and tags the likely fault: easy bench fixes (HDMI, fan, software, disc drive), gambles (no power, light of death, overheating, liquid) and red flags (banned, parts missing)
- Separate tabs for Buy it now, Auctions and Controllers (faulty DualSense and Xbox controllers – drift, buttons, charging)
- Sort by newest, cheapest or auctions ending soon; tap a listing to open it in the eBay app
- Checks in the background every 15/30/60 minutes and notifies you about new listings
- Long-press a listing to hide it

## Setup
1. Sign in at developer.ebay.com with your eBay account and create a **Production** keyset under Application Keys. If you're asked about account deletion notifications, choose the exemption (this app stores no eBay user data).
2. In the app, open Settings and paste the **App ID (Client ID)** and **Cert ID (Client Secret)**, then tap **Test keys**.

Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases**.
