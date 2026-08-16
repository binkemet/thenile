// Minimal device-mapper "crypt" driver over raw DM ioctls.
//
// Why this exists: Android has no `cryptsetup`, and `dmctl` (the on-device DM tool) has no
// `crypt` target in its frontend — but the kernel `crypt` target IS registered
// (`dmctl list targets` → crypt 1.24.0). So we talk to /dev/mapper/control ourselves.
// This is plain dm-crypt (no LUKS header) — which is also better for deniability: nothing
// on disk identifies the container as encrypted.
//
// ponytail: raw ioctls, no libdevmapper. If the struct/ioctl ABI ever drifts, the failure is
// loud (ioctl returns <0 with errno) — see the Result strings.

#![cfg(unix)]

use std::ffi::CString;
use std::io::Read;

const DM_IOCTL: u64 = 0xfd;
const DM_NAME_LEN: usize = 128;
const DM_UUID_LEN: usize = 129;
const DM_STRUCT_SIZE: u64 = 312; // sizeof(struct dm_ioctl); encoded into every dm ioctl number

const DM_DEV_CREATE_CMD: u64 = 3;
const DM_DEV_REMOVE_CMD: u64 = 4;
const DM_DEV_SUSPEND_CMD: u64 = 6; // flags without DM_SUSPEND_FLAG == resume/activate
const DM_TABLE_LOAD_CMD: u64 = 9;

// _IOWR(DM_IOCTL, cmd, struct dm_ioctl): dir=3 (READ|WRITE) in bits 30-31.
fn iowr(cmd: u64) -> u64 {
    (3 << 30) | (DM_STRUCT_SIZE << 16) | (DM_IOCTL << 8) | cmd
}

#[repr(C)]
struct DmIoctl {
    version: [u32; 3],
    data_size: u32,
    data_start: u32,
    target_count: u32,
    open_count: i32,
    flags: u32,
    event_nr: u32,
    padding: u32,
    dev: u64,
    name: [u8; DM_NAME_LEN],
    uuid: [u8; DM_UUID_LEN],
    data: [u8; 7],
}

#[repr(C)]
struct DmTargetSpec {
    sector_start: u64,
    length: u64,
    status: i32,
    next: u32,
    target_type: [u8; 16],
}

fn errno_str() -> String {
    std::io::Error::last_os_error().to_string()
}

/// Open the DM control device. Android often lacks the /dev/mapper/control node, so create it
/// from the misc major (10) and the device-mapper minor read from /proc/misc.
fn open_control() -> Result<i32, String> {
    let path = "/dev/mapper/control";
    unsafe {
        let c = CString::new(path).unwrap();
        let fd = libc::open(c.as_ptr(), libc::O_RDWR);
        if fd >= 0 {
            return Ok(fd);
        }
        // Not present — mknod it. Find the device-mapper misc minor.
        let mut buf = String::new();
        std::fs::File::open("/proc/misc")
            .and_then(|mut f| f.read_to_string(&mut buf))
            .map_err(|e| format!("read /proc/misc: {e}"))?;
        let minor: u64 = buf
            .lines()
            .find(|l| l.contains("device-mapper"))
            .and_then(|l| l.split_whitespace().next())
            .and_then(|n| n.parse().ok())
            .ok_or("device-mapper not in /proc/misc")?;
        std::fs::create_dir_all("/dev/mapper").ok();
        let dev = libc::makedev(10, minor as u32); // misc major = 10
        if libc::mknod(c.as_ptr(), libc::S_IFCHR | 0o600, dev) != 0 {
            return Err(format!("mknod {path}: {}", errno_str()));
        }
        let fd = libc::open(c.as_ptr(), libc::O_RDWR);
        if fd < 0 {
            return Err(format!("open {path} after mknod: {}", errno_str()));
        }
        Ok(fd)
    }
}

fn base_header(name: &str, buf_size: u32, target_count: u32) -> DmIoctl {
    let mut h: DmIoctl = unsafe { std::mem::zeroed() };
    h.version = [4, 0, 0];
    h.data_size = buf_size;
    h.data_start = DM_STRUCT_SIZE as u32;
    h.target_count = target_count;
    let nb = name.as_bytes();
    h.name[..nb.len()].copy_from_slice(nb);
    h
}

unsafe fn dm_ioctl(fd: i32, cmd: u64, buf: &mut [u8]) -> Result<(), String> {
    if libc::ioctl(fd, iowr(cmd) as libc::Ioctl, buf.as_mut_ptr()) < 0 {
        return Err(format!("dm ioctl cmd={cmd}: {}", errno_str()));
    }
    Ok(())
}

/// Create an active dm-crypt device `name` over `backing_dev` and return its /dev/block/dm-N path.
/// `params_key` is the raw hex key; cipher is e.g. "aes-cbc-essiv:sha256".
pub fn create_crypt(
    name: &str,
    cipher: &str,
    key_hex: &str,
    backing_dev: &str,
    sectors: u64,
) -> Result<String, String> {
    if name.len() >= DM_NAME_LEN {
        return Err("name too long".into());
    }
    let fd = open_control()?;
    let close = || unsafe { libc::close(fd) };

    // 1. DM_DEV_CREATE
    let mut hdr = base_header(name, DM_STRUCT_SIZE as u32, 0);
    let mut hbuf = header_bytes(&hdr);
    if let Err(e) = unsafe { dm_ioctl(fd, DM_DEV_CREATE_CMD, &mut hbuf) } {
        close();
        return Err(e);
    }
    // dev number is returned in the header.
    hdr = read_header(&hbuf);
    let dev = hdr.dev;

    // 2. DM_TABLE_LOAD with a single crypt target.
    let params = format!("{cipher} {key_hex} 0 {backing_dev} 0");
    let spec_off = DM_STRUCT_SIZE as usize;
    let spec_size = std::mem::size_of::<DmTargetSpec>();
    // next = offset from the target spec to the following one, 8-byte aligned.
    let param_len = params.len() + 1; // include NUL
    let next = ((spec_size + param_len + 7) / 8) * 8;
    let total = spec_off + next;

    let mut buf = vec![0u8; total];
    let h = base_header(name, total as u32, 1);
    buf[..spec_off].copy_from_slice(&header_bytes(&h));

    let mut spec: DmTargetSpec = unsafe { std::mem::zeroed() };
    spec.sector_start = 0;
    spec.length = sectors;
    spec.next = next as u32;
    spec.target_type[.."crypt".len()].copy_from_slice(b"crypt");
    unsafe {
        std::ptr::copy_nonoverlapping(
            &spec as *const _ as *const u8,
            buf[spec_off..].as_mut_ptr(),
            spec_size,
        );
    }
    buf[spec_off + spec_size..spec_off + spec_size + params.len()]
        .copy_from_slice(params.as_bytes());

    if let Err(e) = unsafe { dm_ioctl(fd, DM_TABLE_LOAD_CMD, &mut buf) } {
        close();
        let _ = remove(name); // don't leak the half-created device
        return Err(e);
    }

    // 3. DM_DEV_SUSPEND with flags=0 -> resume (activate the loaded table).
    let mut rbuf = header_bytes(&base_header(name, DM_STRUCT_SIZE as u32, 0));
    if let Err(e) = unsafe { dm_ioctl(fd, DM_DEV_SUSPEND_CMD, &mut rbuf) } {
        close();
        let _ = remove(name);
        return Err(e);
    }
    close();

    // Ensure the block node exists before returning. ueventd creates it asynchronously on the
    // create+resume uevent; in a restricted (e.g. magisk) domain our own mknod may be denied.
    // Poll up to ~2s: try our own mknod once, otherwise wait for ueventd. Error if it never shows,
    // so callers never mount a path that doesn't exist.
    let major = ((dev >> 8) & 0xfff) as u32;
    let minor = ((dev & 0xff) | ((dev >> 12) & 0xfff00)) as u32;
    let node = format!("/dev/block/dm-{minor}");
    let c = CString::new(node.clone()).unwrap();
    unsafe {
        for i in 0..40 {
            if libc::access(c.as_ptr(), libc::F_OK) == 0 {
                return Ok(node);
            }
            if i == 0 {
                libc::mknod(c.as_ptr(), libc::S_IFBLK | 0o600, libc::makedev(major, minor));
            }
            libc::usleep(50_000);
        }
    }
    let _ = remove(name);
    Err(format!("dm node {node} never appeared (ueventd/mknod)"))
}

/// Remove a dm device by name.
pub fn remove(name: &str) -> Result<(), String> {
    let fd = open_control()?;
    let mut buf = header_bytes(&base_header(name, DM_STRUCT_SIZE as u32, 0));
    let r = unsafe { dm_ioctl(fd, DM_DEV_REMOVE_CMD, &mut buf) };
    unsafe { libc::close(fd) };
    r
}

fn header_bytes(h: &DmIoctl) -> Vec<u8> {
    let mut v = vec![0u8; DM_STRUCT_SIZE as usize];
    unsafe {
        std::ptr::copy_nonoverlapping(h as *const _ as *const u8, v.as_mut_ptr(), v.len());
    }
    v
}

fn read_header(buf: &[u8]) -> DmIoctl {
    let mut h: DmIoctl = unsafe { std::mem::zeroed() };
    unsafe {
        std::ptr::copy_nonoverlapping(
            buf.as_ptr(),
            &mut h as *mut _ as *mut u8,
            DM_STRUCT_SIZE as usize,
        );
    }
    h
}
