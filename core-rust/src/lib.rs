//! Quest-Mirror Core - Crate Rust client scrcpy

use std::io::Read;
use std::net::TcpStream;
use std::os::raw::{c_char, c_int, c_void};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const DEFAULT_PORT: u16 = 27183;

struct ClientState {
    stream: Option<TcpStream>,
    is_connected: bool,
}

struct ScrcpyClient {
    state: Arc<Mutex<ClientState>>,
}

impl ScrcpyClient {
    fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(ClientState {
                stream: None,
                is_connected: false,
            })),
        }
    }

    fn connect(&self, ip: &str, port: u16) -> Result<(), String> {
        let stream = TcpStream::connect(format!("{}:{}", ip, port))
            .map_err(|e| format!("Failed to connect to {}:{} : {}", ip, port, e))?;
        
        stream.set_read_timeout(Some(Duration::from_secs(5)))
            .map_err(|e| format!("set_read_timeout failed: {}", e))?;
        
        let mut state = self.state.lock().unwrap();
        state.stream = Some(stream);
        state.is_connected = true;
        
        Ok(())
    }

    fn disconnect(&self) {
        let mut state = self.state.lock().unwrap();
        state.stream = None;
        state.is_connected = false;
    }

    fn read_frame(&self, buffer: &mut [u8]) -> i32 {
        let mut state = self.state.lock().unwrap();
        if !state.is_connected {
            return -1;
        }
        let stream = state.stream.as_mut().unwrap();
        match stream.read(buffer) {
            Ok(n) => n as i32,
            Err(_) => {
                state.is_connected = false;
                state.stream = None;
                -1
            }
        }
    }

    fn is_connected(&self) -> bool {
        self.state.lock().unwrap().is_connected
    }
}

// Global client stored as raw pointer (we manage lifetime manually)
static mut G_CLIENT: *mut ScrcpyClient = std::ptr::null_mut();

#[no_mangle]
pub extern "C" fn Java_com_example_quest_jni_Core_nativeCreateClient() -> bool {
    unsafe {
        // Si le client est déjà créé, ne rien faire et retourner true
        if !G_CLIENT.is_null() {
            return true;
        }
        G_CLIENT = Box::into_raw(Box::new(ScrcpyClient::new()));
        true
    }
}

#[no_mangle]
pub extern "C" fn Java_com_example_quest_jni_Core_nativeDestroyClient() {
    unsafe {
        if !G_CLIENT.is_null() {
            let _ = Box::from_raw(G_CLIENT);
            G_CLIENT = std::ptr::null_mut();
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_example_quest_jni_Core_nativeConnect(
    _env: *mut c_void,
    _thiz: *mut c_void,
    ip: *const c_char,
    port: c_int,
) -> bool {
    if ip.is_null() { return false; }
    
    let ip_str = unsafe { std::ffi::CStr::from_ptr(ip).to_string_lossy().into_owned() };
    
    unsafe {
        if G_CLIENT.is_null() {
            // Si le client n'est pas créé, essayons de le créer maintenant
            G_CLIENT = Box::into_raw(Box::new(ScrcpyClient::new()));
        }
        (*G_CLIENT).connect(&ip_str, port as u16).is_ok()
    }
}

#[no_mangle]
pub extern "C" fn Java_com_example_quest_jni_Core_nativeDisconnect(
    _env: *mut c_void,
    _thiz: *mut c_void,
) {
    unsafe {
        if !G_CLIENT.is_null() {
            (*G_CLIENT).disconnect();
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_example_quest_jni_Core_nativeIsConnected(
    _env: *mut c_void,
    _thiz: *mut c_void,
) -> bool {
    unsafe {
        !G_CLIENT.is_null() && (*G_CLIENT).is_connected()
    }
}

#[no_mangle]
pub extern "C" fn Java_com_example_quest_jni_Core_nativeReadFrame(
    _env: *mut c_void,
    _thiz: *mut c_void,
    buffer: *mut u8,
    buffer_len: c_int,
) -> c_int {
    if buffer.is_null() || buffer_len <= 0 { return -1; }
    
    unsafe {
        if G_CLIENT.is_null() { return -1; }
        let slice = std::slice::from_raw_parts_mut(buffer, buffer_len as usize);
        (*G_CLIENT).read_frame(slice)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_port_constant() {
        assert_eq!(DEFAULT_PORT, 27183);
    }
}
