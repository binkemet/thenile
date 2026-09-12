use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};

use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};

/// Hash a PIN for enrollment/storage. PHC string embeds a fresh random salt — correct for
/// verification (see verify_pin), NOT for key derivation (see derive_key).
#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_root_StorageMountManager_hashPin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pin: JString<'local>,
) -> jstring {
    let input: String = env.get_string(&pin).expect("bad pin").into();
    let salt = SaltString::generate(&mut OsRng);
    let hash = Argon2::default()
        .hash_password(input.as_bytes(), &salt)
        .expect("hash failed")
        .to_string();
    env.new_string(hash).expect("new_string failed").into_raw()
}

/// Verify a PIN against a stored PHC hash. Constant-time comparison via argon2.
#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_root_StorageMountManager_verifyPin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pin: JString<'local>,
    stored: JString<'local>,
) -> jboolean {
    let input: String = env.get_string(&pin).expect("bad pin").into();
    let hash_str: String = env.get_string(&stored).expect("bad hash").into();
    let parsed = match PasswordHash::new(&hash_str) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };
    match Argon2::default().verify_password(input.as_bytes(), &parsed) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

/// Deterministically derive a 64-byte (AES-256-XTS) key from the PIN and a caller-supplied stable
/// salt. Same (pin, salt) always yields the same key, so the container reopens across mounts/devices.
#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_root_StorageMountManager_deriveKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pin: JString<'local>,
    salt: JString<'local>,
) -> jstring {
    let input: String = env.get_string(&pin).expect("bad pin").into();
    let salt_str: String = env.get_string(&salt).expect("bad salt").into();
    // 64 bytes = a 512-bit key for AES-256-XTS (dm-crypt "aes-xts-plain64" splits it into two
    // 256-bit halves). XTS is the standard for block-device encryption; see StorageMountManager.
    let mut out = [0u8; 64];
    Argon2::default()
        .hash_password_into(input.as_bytes(), salt_str.as_bytes(), &mut out)
        .expect("derive failed");
    let hex: String = out.iter().map(|b| format!("{:02x}", b)).collect();
    env.new_string(hex).expect("new_string failed").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_backup_BackupManager_encryptPayloadNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pass: JString<'local>,
    salt: JString<'local>,
    iv_bytes: JByteArray<'local>,
    plaintext: JString<'local>,
) -> jstring {
    let password: String = env.get_string(&pass).expect("bad pass").into();
    let salt_str: String = env.get_string(&salt).expect("bad salt").into();
    let text: String = env.get_string(&plaintext).expect("bad plaintext").into();

    let mut key = [0u8; 32];
    Argon2::default()
        .hash_password_into(password.as_bytes(), salt_str.as_bytes(), &mut key)
        .expect("argon2 key derive failed");

    let iv_vec = env.convert_byte_array(&iv_bytes).expect("bad iv");
    let nonce = Nonce::from_slice(&iv_vec);

    let cipher = Aes256Gcm::new_from_slice(&key).expect("cipher init failed");
    let ciphertext = cipher.encrypt(nonce, text.as_bytes()).expect("encryption failed");

    let hex_out: String = ciphertext.iter().map(|b| format!("{:02x}", b)).collect();
    env.new_string(hex_out).expect("new_string failed").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_backup_BackupManager_decryptPayloadNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pass: JString<'local>,
    salt: JString<'local>,
    iv_bytes: JByteArray<'local>,
    ciphertext_hex: JString<'local>,
) -> jstring {
    let password: String = env.get_string(&pass).expect("bad pass").into();
    let salt_str: String = env.get_string(&salt).expect("bad salt").into();
    let hex_str: String = env.get_string(&ciphertext_hex).expect("bad ciphertext_hex").into();

    let mut key = [0u8; 32];
    Argon2::default()
        .hash_password_into(password.as_bytes(), salt_str.as_bytes(), &mut key)
        .expect("argon2 key derive failed");

    let iv_vec = env.convert_byte_array(&iv_bytes).expect("bad iv");
    let nonce = Nonce::from_slice(&iv_vec);

    let ciphertext = (0..hex_str.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex_str[i..i + 2], 16))
        .collect::<Result<Vec<u8>, _>>()
        .expect("bad hex string");

    let cipher = Aes256Gcm::new_from_slice(&key).expect("cipher init failed");
    let plaintext_bytes = cipher.decrypt(nonce, ciphertext.as_ref()).expect("decryption failed");

    let plaintext = String::from_utf8(plaintext_bytes).expect("utf8 decode failed");
    env.new_string(plaintext).expect("new_string failed").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_root_StorageMountManager_encryptFileNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pin: JString<'local>,
    salt: JString<'local>,
    src_path: JString<'local>,
    dst_path: JString<'local>,
) -> jboolean {
    use rand_core::RngCore;
    let pin_str: String = match env.get_string(&pin) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let salt_str: String = match env.get_string(&salt) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let src: String = match env.get_string(&src_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let dst: String = match env.get_string(&dst_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let data = match std::fs::read(&src) {
        Ok(d) => d,
        Err(_) => return JNI_FALSE,
    };

    let mut key = [0u8; 32];
    if Argon2::default()
        .hash_password_into(pin_str.as_bytes(), salt_str.as_bytes(), &mut key)
        .is_err()
    {
        return JNI_FALSE;
    }

    let mut iv = [0u8; 12];
    OsRng.fill_bytes(&mut iv);
    let nonce = Nonce::from_slice(&iv);

    let cipher = match Aes256Gcm::new_from_slice(&key) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };

    let ciphertext = match cipher.encrypt(nonce, data.as_ref()) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };

    // Format: [12 bytes IV] + [ciphertext + 16 bytes tag]
    let mut output = Vec::with_capacity(12 + ciphertext.len());
    output.extend_from_slice(&iv);
    output.extend_from_slice(&ciphertext);

    if let Some(parent) = std::path::Path::new(&dst).parent() {
        let _ = std::fs::create_dir_all(parent);
    }

    match std::fs::write(&dst, &output) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_thenile_vault_root_StorageMountManager_decryptFileNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pin: JString<'local>,
    salt: JString<'local>,
    src_path: JString<'local>,
    dst_path: JString<'local>,
) -> jboolean {
    let pin_str: String = match env.get_string(&pin) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let salt_str: String = match env.get_string(&salt) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let src: String = match env.get_string(&src_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let dst: String = match env.get_string(&dst_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let payload = match std::fs::read(&src) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };

    if payload.len() < 12 + 16 {
        return JNI_FALSE;
    }

    let iv = &payload[0..12];
    let ciphertext = &payload[12..];

    let mut key = [0u8; 32];
    if Argon2::default()
        .hash_password_into(pin_str.as_bytes(), salt_str.as_bytes(), &mut key)
        .is_err()
    {
        return JNI_FALSE;
    }

    let nonce = Nonce::from_slice(iv);
    let cipher = match Aes256Gcm::new_from_slice(&key) {
        Ok(c) => c,
        Err(_) => return JNI_FALSE,
    };

    let plaintext = match cipher.decrypt(nonce, ciphertext) {
        Ok(p) => p,
        Err(_) => return JNI_FALSE,
    };

    if let Some(parent) = std::path::Path::new(&dst).parent() {
        let _ = std::fs::create_dir_all(parent);
    }

    match std::fs::write(&dst, &plaintext) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn derive(pin: &str, salt: &str) -> [u8; 64] {
        let mut out = [0u8; 64];
        Argon2::default()
            .hash_password_into(pin.as_bytes(), salt.as_bytes(), &mut out)
            .unwrap();
        out
    }

    #[test]
    fn verify_roundtrip() {
        let salt = SaltString::generate(&mut OsRng);
        let hash = Argon2::default()
            .hash_password(b"4821", &salt)
            .unwrap()
            .to_string();
        let parsed = PasswordHash::new(&hash).unwrap();
        assert!(Argon2::default().verify_password(b"4821", &parsed).is_ok());
        assert!(Argon2::default().verify_password(b"0000", &parsed).is_err());
    }

    #[test]
    fn derive_is_deterministic_and_pin_sensitive() {
        let salt = "0123456789abcdef";
        assert_eq!(derive("4821", salt), derive("4821", salt));
        assert_ne!(derive("4821", salt), derive("4822", salt));
    }

    #[test]
    fn aes_gcm_encrypt_decrypt_roundtrip() {
        let password = "SecretPassword123";
        let salt_str = "0123456789abcdef";
        let iv = [0u8; 12];
        let plaintext = "Hello Nile Encryption!";

        let mut key = [0u8; 32];
        Argon2::default()
            .hash_password_into(password.as_bytes(), salt_str.as_bytes(), &mut key)
            .unwrap();

        let nonce = Nonce::from_slice(&iv);
        let cipher = Aes256Gcm::new_from_slice(&key).unwrap();

        let ciphertext = cipher.encrypt(nonce, plaintext.as_bytes()).unwrap();
        let decrypted_bytes = cipher.decrypt(nonce, ciphertext.as_ref()).unwrap();
        let decrypted = String::from_utf8(decrypted_bytes).unwrap();

        assert_eq!(plaintext, decrypted);
    }
}

