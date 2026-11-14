//! The computer helper on the phone (file 24 C49): pairing, the link to the paired computer and its status for
//! Kotlin. The protocol, the reconnects and the fill live in the core (`lectrice-link`, `LinkSpeechHelper`,
//! `HelperFill`); this module only adapts them to Kotlin.
//!
//! **Why there is no `unsafe` here:** the link must only ever use the Wi-Fi network (file 24 §3.10), so its socket is
//! bound to that network before it connects. Instead of calling the NDK's `android_setsocknetwork` (an `extern "C"`
//! call) or taking over a file descriptor Kotlin made (`FromRawFd`), the socket is created unconnected with
//! `socket2`'s safe API, and Kotlin binds it through the public `Network.bindSocket(FileDescriptor)` on a duplicate
//! of the descriptor ([`NetworkBinder`]); a duplicate shares the socket, so the binding holds. The core keeps
//! `unsafe_code = "forbid"`, and this crate needs no audited block.

use std::io;
use std::net::{SocketAddr, TcpStream};
use std::os::fd::AsRawFd;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use lectrice_domain::{AppResult, HelperEngine, HelperEvent, HelperEvents, HelperItem, HelperState, SpeechHelper};
use lectrice_infra::helper::{LinkHelperConfig, LinkSpeechHelper};
use lectrice_link::client::{ClientConfig, SocketFactory, pair_via};
use lectrice_link::limits::Timing;
use lectrice_link::{LinkError, PairingCode, PairingFailure, StaticKeys};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tracing::{info, warn};

use crate::errors::CoreErrorInfo;

const TARGET: &str = "lectrice_ffi::helper";
const KEY_BYTES: usize = 32;

/// Kotlin: binds a socket to the current Wi-Fi (or Ethernet) network.
#[uniffi::export(with_foreign)]
pub trait NetworkBinder: Send + Sync {
    /// Binds the unconnected socket `fd` (still owned by Rust; Kotlin may only use a duplicate) to the home network.
    /// `false` when the phone has no Wi-Fi or Ethernet network now: the link then doesn't connect at all.
    fn bind_socket(&self, fd: i32) -> bool;
}

/// Kotlin: hears every link state change at once (on the link's thread; must return quickly).
#[uniffi::export(with_foreign)]
pub trait HelperListener: Send + Sync {
    fn on_state(&self, state: HelperStateRecord);
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HelperStateRecord {
    Off,
    Connecting,
    Connected,
    /// Connected, but the computer's own listener comes first for now.
    Busy,
    Lost,
    /// Won't work until the person acts (`HelperNotPaired`, `HelperModelMissing`, `HelperIncompatible`).
    NeedsAttention {
        error: CoreErrorInfo,
    },
}

impl From<&HelperState> for HelperStateRecord {
    fn from(state: &HelperState) -> Self {
        match state {
            HelperState::Off => Self::Off,
            HelperState::Connecting => Self::Connecting,
            HelperState::Connected => Self::Connected,
            HelperState::Busy => Self::Busy,
            HelperState::Lost => Self::Lost,
            HelperState::NeedsAttention(name) => Self::NeedsAttention { error: CoreErrorInfo::from(*name) },
        }
    }
}

/// A paired computer: public data only (the phone's `peers.json` holds these).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HelperComputer {
    /// First 16 hex digits of SHA-256(its static key): the `id` in its mDNS record.
    pub peer_id: String,
    /// The name it gave while pairing (≤ 40 characters, checked by the link).
    pub name: String,
    pub static_public: Vec<u8>,
}

/// Everything the link needs to reach one paired computer.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HelperConfig {
    /// This phone's X25519 private key (32 bytes, unwrapped from the Keystore by Kotlin; never logged).
    pub private_key: Vec<u8>,
    pub computer: HelperComputer,
    /// "ip:port" candidates, tried in order (NSD answers first, then the last address that worked).
    pub addresses: Vec<String>,
    pub device_name: String,
    pub app_version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum PairOutcome {
    Paired {
        computer: HelperComputer,
    },
    /// A guess was made and was wrong (the computer counts it: 3 per code, then the code is gone).
    WrongCode,
    /// No code is showing on the computer (never opened, expired, or closed after 3 wrong codes).
    NoCode,
    /// The typed address isn't on the home network.
    NotLocal,
    /// Another protocol version: one of the two apps is older.
    Incompatible,
    /// Couldn't reach the computer (wrong address, firewall, not on this Wi-Fi).
    Unreachable,
}

/// The phone's own id for a public key, as the computer advertises it.
#[uniffi::export]
pub fn helper_peer_id(static_public: Vec<u8>) -> Option<String> {
    let key: [u8; KEY_BYTES] = static_public.try_into().ok()?;
    Some(lectrice_link::PeerId::of(&key).to_string())
}

/// The local read-ahead's minutes once the computer is lost (file 24 §3.6; the rule lives in the core).
#[uniffi::export]
pub fn helper_lost_read_ahead_minutes(rtf_median: Option<f32>, rate: f32, setting_minutes: u32) -> u32 {
    lectrice_app::speech::lost_read_ahead_minutes(rtf_median, rate, setting_minutes)
}

/// Pairs with the computer at `address` ("ip:port") using the code its screen shows. Blocks up to the handshake time.
pub(crate) fn pair(binder: Arc<dyn NetworkBinder>, address: &str, code: &str, private_key: Vec<u8>, device_name: String, app_version: String) -> PairOutcome {
    let Ok(address) = address.trim().parse::<SocketAddr>() else { return PairOutcome::Unreachable };
    let Some(code) = PairingCode::parse(code) else { return PairOutcome::WrongCode };
    let Some(keys) = keys_from(private_key) else { return PairOutcome::Unreachable };
    let config = client_config(device_name, app_version);
    match pair_via(&WifiSockets(binder), address, &code, &keys, &config) {
        Ok(paired) => {
            let peer = paired.peer;
            info!(target: TARGET, peer = peer.peer_id.short(), "paired with a computer");
            PairOutcome::Paired { computer: HelperComputer { peer_id: peer.peer_id.to_string(), name: peer.name, static_public: peer.static_public.to_vec() } }
        }
        Err(error) => {
            warn!(target: TARGET, error = %error, "pairing failed");
            match error {
                LinkError::PairingFailed(PairingFailure::WrongCode) => PairOutcome::WrongCode,
                LinkError::PairingFailed(PairingFailure::NoCodeShowing) => PairOutcome::NoCode,
                LinkError::NotLocal => PairOutcome::NotLocal,
                LinkError::Incompatible(_) => PairOutcome::Incompatible,
                _ => PairOutcome::Unreachable,
            }
        }
    }
}

/// The helper as the core's port, plus the handles the shell drives (addresses, retries).
pub(crate) struct HelperLink {
    link: Arc<LinkSpeechHelper>,
    pub(crate) port: Arc<dyn SpeechHelper>,
    pub(crate) peer_id: String,
}

impl HelperLink {
    pub(crate) fn new(config: HelperConfig, binder: Arc<dyn NetworkBinder>, listener: Arc<dyn HelperListener>) -> Option<Self> {
        let HelperConfig { private_key, computer, addresses, device_name, app_version } = config;
        let keys = keys_from(private_key)?;
        let computer_key: [u8; KEY_BYTES] = computer.static_public.try_into().ok()?;
        let link = Arc::new(LinkSpeechHelper::new(LinkHelperConfig {
            keys,
            computer_key,
            addresses: parse_addresses(&addresses),
            client: client_config(device_name, app_version),
            sockets: Arc::new(WifiSockets(binder)),
        }));
        let port: Arc<dyn SpeechHelper> = Arc::new(Reporting { inner: link.clone(), listener });
        Some(Self { link, port, peer_id: computer.peer_id })
    }

    pub(crate) fn set_addresses(&self, addresses: &[String]) {
        self.link.set_addresses(parse_addresses(addresses));
    }

    pub(crate) fn retry_now(&self) {
        self.link.retry_now();
    }
}

fn keys_from(private_key: Vec<u8>) -> Option<StaticKeys> {
    let private: [u8; KEY_BYTES] = private_key.try_into().ok()?;
    Some(StaticKeys::from_private(private))
}

fn client_config(device_name: String, app_version: String) -> ClientConfig {
    ClientConfig { device_name, app_version, timing: Timing::default(), allow_loopback: false }
}

fn parse_addresses(addresses: &[String]) -> Vec<SocketAddr> {
    addresses.iter().filter_map(|address| address.trim().parse().ok()).collect()
}

/// Passes everything to the link and tells Kotlin about each state change after the app has seen it.
struct Reporting {
    inner: Arc<LinkSpeechHelper>,
    listener: Arc<dyn HelperListener>,
}

struct ReportingEvents {
    inner: Arc<dyn HelperEvents>,
    listener: Arc<dyn HelperListener>,
}

impl HelperEvents for ReportingEvents {
    fn on_event(&self, event: HelperEvent) {
        let state = match &event {
            HelperEvent::State(state) => Some(HelperStateRecord::from(state)),
            _ => None,
        };
        self.inner.on_event(event);
        if let Some(state) = state {
            self.listener.on_state(state);
        }
    }
}

impl SpeechHelper for Reporting {
    fn state(&self) -> HelperState {
        self.inner.state()
    }

    fn open(&self, engine: HelperEngine, events: Arc<dyn HelperEvents>) -> AppResult<()> {
        self.inner.open(engine, Arc::new(ReportingEvents { inner: events, listener: self.listener.clone() }))
    }

    fn make(&self, items: Vec<HelperItem>) -> AppResult<()> {
        self.inner.make(items)
    }

    fn cancel_all(&self) -> AppResult<()> {
        self.inner.cancel_all()
    }

    fn close(&self) {
        self.inner.close();
        self.listener.on_state(HelperStateRecord::Off);
    }
}

/// TCP sockets bound to the home network by Kotlin before they connect.
struct WifiSockets(Arc<dyn NetworkBinder>);

impl SocketFactory for WifiSockets {
    fn connect(&self, address: SocketAddr, timeout: Duration) -> io::Result<TcpStream> {
        let socket = Socket::new(Domain::for_address(address), Type::STREAM, Some(Protocol::TCP))?;
        if !self.0.bind_socket(socket.as_raw_fd()) {
            return Err(io::Error::new(io::ErrorKind::NetworkUnreachable, "no Wi-Fi network to bind to"));
        }
        socket.connect_timeout(&SockAddr::from(address), timeout)?;
        Ok(socket.into())
    }
}

pub(crate) fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}
