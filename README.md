# Salvage

Android app that watches eBay UK for faulty PS5, PS5 Pro and Xbox Series X/S consoles, controllers, Nvidia RTX graphics cards (20, 30, 40 and 50 series) and RTX gaming laptops under your own price limits, so you can buy, repair and flip them.

- Uses eBay's official Browse API: "For parts or not working" listings in the Video Game Consoles category, located in the UK
- Separate max price for each console (postage included)
- Reads the title and tags the likely fault: easy bench fixes (HDMI, fan, software, disc drive), gambles (no power, light of death, overheating, liquid) and red flags (banned, parts missing)
- Sections for Consoles, Controllers, GPUs and Laptops, each split into Buy it now and Auctions
- GPU and laptop fault tags: no display, artifacts, burnt, ex-mining, port damage, screen, keyboard, charging, locked BIOS
- Sort by newest, cheapest or auctions ending soon; tap a listing to open it in the eBay app
- Reads each new listing's condition notes and description and shows what the seller says is wrong ("Seller says: …"), adding fault tags it finds there
- Alert price per item: only Buy it now listings under it send a notification (plus easy-fix faults up to 20% over); prices that would alert show in green
- Checks in the background every 5/15/30/60 minutes and notifies you about new listings
- Auction reminders: tap the bell on an auction to be told a set time before it ends (with the live bid); auctions still under the alert price get reminders automatically
- Long-press a listing to hide it

## Setup
1. Sign in at developer.ebay.com with your eBay account and create a **Production** keyset under Application Keys. If you're asked about account deletion notifications, choose the exemption (this app stores no eBay user data).
2. In the app, open Settings and paste the **App ID (Client ID)** and **Cert ID (Client Secret)**, then tap **Test keys**.

Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases**.
