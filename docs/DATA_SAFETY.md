# Data safety and content rating: answers to enter in Play Console

Answer for the build as it is. If you add analytics, crash reporting or login later, update the form first.

## Data safety form
**Does the app collect or share user data?** Yes (because of ads and purchases).
**Is all data encrypted in transit?** Yes. **Can users request deletion?** No account exists; data is only on the device (uninstall removes it).

| Data type | Collected | Shared | Purpose | Notes |
|---|---|---|---|---|
| Device or other IDs (Advertising ID) | Yes | Yes, with Google (AdMob) | Advertising | Not required to use the app; Pro removes ads |
| App interactions / diagnostics (ad SDK) | Yes | Yes, with Google (AdMob) | Advertising, fraud prevention | Collected by the AdMob SDK |
| Approximate location (IP-based, ad SDK) | Yes | Yes, with Google (AdMob) | Advertising | Collected by the AdMob SDK |
| Purchase history | Processed by Google Play | No | App functionality | We only receive "Pro unlocked" |
| Files and docs, photos, videos | **No** | **No** | n/a | Read and processed on the device only; never leave it |

Select "Data is processed ephemerally / stays on device" where the form offers it for files. Do not tick "Photos and videos" or "Files and docs" as collected, because nothing is sent off the device.

## Ads declaration
Contains ads: **Yes**.

## Target audience and content
Target age: **18 and over**. Not designed for children. No Families program.

## Content rating questionnaire (IARC)
Category: **Utility, productivity, communication or other**.
Answer **No** to violence, sexual content, profanity, controlled substances, gambling, user-generated content, location sharing, and online interaction between users. Answer **Yes** only to "contains ads" if asked. Expected result: Everyone / PEGI 3.

## Other App content declarations
- Government app: No. Financial features: No. Health features: No. News app: No.
- Advertising ID: Yes, used for advertising (the manifest includes the AD_ID permission).
- Data deletion: not applicable (no account).
