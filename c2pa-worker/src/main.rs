//! Local C2PA inspection and development signing worker.
fn main() {
    if let Err(error) = execute() {
        eprintln!("C2PA operation failed: {error}");
        std::process::exit(1);
    }
}
fn execute() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if matches!(
        args.first().map(String::as_str),
        Some("sign" | "sign-pkcs11")
    ) {
        if args.len() != 6 {
            return Err("Usage: sign <input> <output> <manifest> <certificate-chain> <key>".into());
        }
        let definition = std::fs::read_to_string(&args[3])?;
        let mut builder = c2pa::Builder::from_context(c2pa::Context::new())
            .with_definition(definition.as_str())?;
        builder.set_intent(c2pa::BuilderIntent::Edit);
        let format = match std::path::Path::new(&args[1])
            .extension()
            .and_then(|e| e.to_str())
        {
            Some("png") => "image/png",
            Some("jpg" | "jpeg") => "image/jpeg",
            Some("webp") => "image/webp",
            Some("tif" | "tiff") => "image/tiff",
            Some("wav") => "audio/wav",
            Some("mp3") => "audio/mpeg",
            Some("flac") => "audio/flac",
            Some("mp4") => "video/mp4",
            Some("pdf") => "application/pdf",
            _ => return Err("Unsupported input extension".into()),
        };
        let mut original = std::fs::File::open(&args[1])?;
        builder.add_ingredient_from_stream(
            r#"{"title":"Original content","relationship":"parentOf"}"#,
            format,
            &mut original,
        )?;
        let signer: Box<dyn c2pa::Signer + Send + Sync> = if args[0] == "sign-pkcs11" {
            let socket = args[5].clone();
            Box::new(c2pa::CallbackSigner::new(
                move |_, data| {
                    use std::io::{Read, Write};
                    let exchange = || -> std::io::Result<Vec<u8>> {
                        if data.is_empty() || data.len() > 65536 {
                            return Err(std::io::Error::other("Invalid signing request size"));
                        }
                        let mut stream = std::os::unix::net::UnixStream::connect(&socket)?;
                        stream.set_read_timeout(Some(std::time::Duration::from_secs(25)))?;
                        stream.set_write_timeout(Some(std::time::Duration::from_secs(5)))?;
                        stream.write_all(&(data.len() as u32).to_be_bytes())?;
                        stream.write_all(data)?;
                        let mut length = [0u8; 4];
                        stream.read_exact(&mut length)?;
                        let length = u32::from_be_bytes(length) as usize;
                        if length == 0 || length > 256 {
                            return Err(std::io::Error::other("Invalid signing response size"));
                        }
                        let mut signature = vec![0; length];
                        stream.read_exact(&mut signature)?;
                        Ok(signature)
                    };
                    exchange().map_err(c2pa::Error::IoError)
                },
                c2pa::SigningAlg::Es256,
                std::fs::read(&args[4])?,
            ))
        } else {
            c2pa::create_signer::from_files(&args[4], &args[5], c2pa::SigningAlg::Es256, None)?
        };
        builder.sign_file(signer.as_ref(), &args[1], &args[2])?;
        let reader = c2pa::Reader::from_context(c2pa::Context::new()).with_file(&args[2])?;
        if reader.validation_state() == c2pa::ValidationState::Invalid {
            std::fs::remove_file(&args[2])?;
            return Err("Signed output failed validation".into());
        }
        println!("{}", reader.json());
    } else {
        if args.len() != 1 {
            return Err("Usage: c2pa-worker <asset-path>".into());
        }
        let reader = c2pa::Reader::from_context(c2pa::Context::new()).with_file(&args[0])?;
        println!("{}", reader.json());
    }
    Ok(())
}
