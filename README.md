# Wallto

A personal, non-custodial Android wallet for EVM chains. Keys are created and kept on the phone, behind the Android Keystore and biometrics. Written in Kotlin with Jetpack Compose.

> **Warning.** This is a personal project. It has not been audited. Use it at your own risk, start with testnets, and never keep more in it than you can afford to lose. Always keep a written backup of every recovery phrase or private key.

## Features

**Wallets**
- Several wallets in one app. Each wallet comes from its own recovery phrase (BIP-44, `m/44'/60'/0'/0/0`) or from an imported secret (phrase or key)
- Contacts, tokens and chains are shared between wallets. History is filtered per wallet.
- Switching wallets needs no biometrics. Signing, revealing secrets and deleting do.

**Chains and tokens**
- Any EVM chain, including testnets. Add a chain by RPC URL and chain ID.
- Tokens per chain. Zero-balance tokens are hidden from the asset list.
- Chain and token icons from the Trust Wallet assets repository for mainnets. They are downloaded once and kept until you remove the chain or token. Testnets and unknown tokens use letter avatars.
- Optional fiat prices from CoinGecko (mainnets only, refreshed once a day or on demand).
- Hide-balances toggle on the assets screen.

**Sending**
- Native and ERC-20 transfers, with ENS name resolution (ASCII names, via Ethereum mainnet).
- A review page with editable max fee, nonce and gas limit, exact amounts under Advanced details, and a note when a recipient is new.
- Unknown function selectors are looked up in the Sourcify 4byte signature database and shown as a hint.
- Saved contacts, with the contact list kept separate from saved contracts.
- Transaction simulation on request (Alchemy) that shows which tokens leave and arrive.

**Contracts**
- Call any contract function. If the contract is verified on Sourcify, its ABI is loaded automatically, including proxy implementations.
- Saved contracts with their ABIs.

**Batches (EIP-7702)**
- For accounts delegated to an ERC-7821 compatible contract (such as Uniswap Calibur), several calls run as one atomic transaction.
- Build batches in the Send screen or in Contracts.
- Set or revoke the delegation of an account in Settings, under Smart account. Authorizations are signed only for the current chain, never for chain 0.

**WalletConnect v2 (Reown)**
- `personal_sign`, `eth_signTypedData_v4`, `eth_sendTransaction`, `wallet_switchEthereumChain`.
- EIP-5792 batches: `wallet_sendCalls`, `wallet_getCallsStatus`, `wallet_getCapabilities`.
- A request is always signed by the wallet the site connected with. The wallet is highlighted if it is not the active one.
- Domain verification, warnings for unlimited approvals and permit-style signatures, and automatic refusal of unsupported methods such as `eth_sign`.
- Not supported: authorization requests (EIP-7702 delegation from a site), SIWE `authenticate`, off-chain (CCIP-Read) ENS names.

## Security design

- Wallet secrets are encrypted with an Android Keystore key that needs biometric authentication. Each use of a secret asks for a biometric prompt, and the decrypted bytes are wiped after use.
- Screenshots, screen recording and the recents thumbnail are blocked. Full-screen dialogs use `SecureFlagPolicy.SecureOn`.
- The app locks after a configurable time in the background. The time is kept in the ViewModel so it survives activity recreation.
- Before signing for a WalletConnect session, the app checks that the derived address equals the session account.
- No secrets in logs. API keys that are not secrets of the wallet (CoinGecko, Alchemy, Reown project ID) are stored on the device only.
- If Android deletes the Keystore key (for example when biometrics change), the app tells you and offers a reset. You then restore each wallet from its phrase or key.

Your recovery phrases are the only backup. If you uninstall the app or lose the Keystore key, nothing else can restore your wallets.

## Build

Requirements: Android Studio, JDK 25 or newer, and an Android device or emulator on API 34 or newer.

```
git clone git@github.com:nzmpi/Wallto.git
cd Wallto
./gradlew assembleDebug
```

For real funds, build a release variant signed with your own key instead of installing the debug build. A debug build is debuggable, so anyone with USB debugging access can read the app's private files.

```
./gradlew assembleRelease
```

Keep the release keystore and its passwords backed up and outside the repository. Switching from a debug-signed install to a release-signed one means uninstalling the app first. That deletes the wallets from the phone, so check that you can restore every wallet from its written phrase before you do it.

## Configuration

Set these in the app under Settings. None of them is required to send funds.

| Setting | What it is for |
|---|---|
| Reown project ID | Needed for WalletConnect. Create one for free at the Reown dashboard. The SDK reads it at startup, so restart the app after changing it. |
| Alchemy key | Transaction simulation. |
| CoinGecko key | Fiat prices (a demo key works). |

## Stack

- Kotlin, Jetpack Compose, a custom dim theme with stroke icons.
- web3j 4.12.2 with BouncyCastle. Type-4 (EIP-7702) transactions are encoded and signed by hand.
- Room for chains, tokens, contacts, wallets, activity and saved contracts.
- Android Keystore (AES-GCM) and BiometricPrompt.
- Reown WalletKit for WalletConnect v2.
- Google code scanner for QR scanning, so the app needs no camera permission.
- ZXing core for generating address QR codes.
- Sourcify (verified ABIs and the 4byte signature service), Trust Wallet assets (icons), CoinGecko (prices), Alchemy (simulation).

## Project layout

```
abi/       ABI codec, Sourcify ABI source, signature lookup
chain/     ENS, RPC, sending, batch, delegation, simulation
core/      credentials, authentication, walletgate
dbs/       databases: activity, chains, tokens, wallets
prices/    CoinGecko price repository
storage/   icon store, keystore vault, pricekey store, contracts, vault
ui/        screens, dialogs, theme, icons, view model
utils/     shared constants, helpers, scanner
wc/        WalletConnect service and request signing
```

## Limits

- EVM chains only.
- The ABI codec handles static types, `bytes`, `string` and arrays of static types. Tuples and nested or fixed-size arrays are not supported, so such calls are shown without decoded arguments.
- Only supports phones with Andriod 14 and higher with biometrics.
- No notifications.  
- Activity only shows outgoing transactions.

## License

MIT