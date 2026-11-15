# Play Store launch checklist

## Before the first upload
- [ ] Create the app in Play Console and enrol in **Play App Signing**.
- [ ] Add the four signing secrets (see README) so CI produces a signed AAB.
- [ ] Create an **AdMob** app + two ad units (Banner, Rewarded). Add GitHub secrets
      `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_REWARDED_ID`. Without them release builds use Google's test ads.
- [ ] Create the in-app product: **Monetize → Products → In-app products**, ID **`pro_unlock`**,
      one-time, price about ₹199–299 / $2.99–4.99 (test it). Activate it.
- [ ] Upload the AAB to **Internal testing**; in-app products only work for builds uploaded to a Play track.
      Add yourself as a license tester (Settings → License testing) to buy without being charged.
- [ ] Do **not** click your own real ads. Use test devices or debug builds (debug always uses test IDs).

## Policy and forms
- [ ] **All files access declaration** → `docs/ALL_FILES_ACCESS_DECLARATION.md`.
- [ ] **Privacy policy URL** (required because the app uses ads and the advertising ID). A draft is in
      `docs/PRIVACY_POLICY.md`: fill in the bracketed items, host it publicly (e.g. GitHub Pages), and
      paste the URL in Play Console.
- [ ] **Data safety form:** declare Advertising ID and device/app info shared with Google AdMob.
      Files and file hashes stay on the device (not collected).
- [ ] **Ads declaration:** "Contains ads" = yes.
- [ ] **Target audience:** adults / 18+ (do not target children, which would restrict ad SDK use).
- [ ] **EEA/UK users:** add Google's consent form (UMP SDK) before serving ads there. Not included yet.

## Store listing: say what is true
- Free scan, preview and trash. Free cleanup up to 500 MB (+500 MB per rewarded ad), cleaning similar photos is Pro only, then a **one-time** Pro unlock. No subscription.
- Works on file *contents*, so renamed copies are found. Nothing is permanently deleted until you empty the trash.
- No "boost", "RAM" or "junk" claims, and no fake alarms.

## After launch
- Watch the Play Console funnel: installs → permission granted → scan finished → paywall shown → purchase.
- Reviews will tell you whether 500 MB is too generous or too tight; adjust `Entitlements.FREE_BYTES`.

## Store listing name
- App title (30 max): `Cloneout: Duplicate Cleaner`
- Launcher label: `Cloneout`
- Short description: "Find and remove duplicate photos, videos and files, safely."
- Check the name is free on Play, as a domain and as a trademark before publishing.

## Testing purchases (why the Pro button says "Loading price…" or "Store unavailable")
Play Billing only returns products to an app installed from Google Play. A sideloaded APK from GitHub Actions
will never show a price. To test the paywall end to end:
1. Create the in-app product `pro_unlock` (one-time) in Play Console and set it to **Active**.
2. Upload the AAB to an **Internal testing** track and add your Google account under Setup > License testing.
3. Install from the internal testing opt-in link (not the APK) with that account, on a phone with Play Store.
4. The price now loads; purchases by licensed testers are free and can be refunded/cleared.
