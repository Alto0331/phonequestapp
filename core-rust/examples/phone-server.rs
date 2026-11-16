// Serveur minimal pour phone - V0
// Compilé avec cargo-ndk pour aarch64-linux-android

use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

const PORT: u16 = 27183;

fn main() {
    println!("=== scrcpy-server (quest-mirror V0) ===");
    println!("Server started on port {}", PORT);
    
    let listener = TcpListener::bind(format!("0.0.0.0:{}", PORT)).unwrap();
    listener.set_nonblocking(true).ok();
    
    println!("Waiting for connection...");
    
    // Accept connections in a loop
    loop {
        match listener.accept() {
            Ok((stream, addr)) => {
                println!("Client connected from: {}", addr);
                thread::spawn(|| handle_client(stream));
            }
            Err(e) => {
                // Non-blocking accept failed, that's ok
                if e.kind() != std::io::ErrorKind::WouldBlock {
                    eprintln!("Accept error: {}", e);
                }
                thread::sleep(Duration::from_millis(100));
            }
        }
    }
}

fn handle_client(mut stream: TcpStream) {
    println!("Handling client connection");
    
    // Send server handshake
    let message = b"SCRCPY Server v0.1 - QuestMirror\nPort: 27183\n";
    if let Err(e) = stream.write_all(message) {
        eprintln!("Failed to send handshake: {}", e);
        return;
    }
    
    // For V0, just keep connection alive and send periodic heartbeat
    loop {
        let heartbeat = b"HEARTBEAT\n";
        let _ = stream.write_all(heartbeat);
        thread::sleep(Duration::from_secs(1));
    }
}
