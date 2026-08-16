// Standalone tester for the dm-crypt ioctl path. Run as root on-device:
//   dmcrypt create <name> <cipher> <keyhex> <backing_dev> <sectors>
//   dmcrypt remove <name>
#[path = "../src/dm.rs"]
mod dm;

#[cfg(unix)]
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let r = match a.get(1).map(|s| s.as_str()) {
        Some("create") if a.len() == 7 => dm::create_crypt(
            &a[2],
            &a[3],
            &a[4],
            &a[5],
            a[6].parse().expect("sectors must be a number"),
        )
        .map(|node| println!("{node}")),
        Some("remove") if a.len() == 3 => dm::remove(&a[2]).map(|_| println!("removed")),
        _ => {
            eprintln!("usage: dmcrypt create <name> <cipher> <keyhex> <dev> <sectors> | remove <name>");
            std::process::exit(2);
        }
    };
    if let Err(e) = r {
        eprintln!("ERROR: {e}");
        std::process::exit(1);
    }
}

#[cfg(not(unix))]
fn main() {
    eprintln!("dmcrypt requires Android/Linux");
}
