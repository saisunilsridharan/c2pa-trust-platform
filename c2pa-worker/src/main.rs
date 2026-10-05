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
        if args.len() != 6 && args.len() != 7 && args.len() != 8 {
            return Err("Usage: sign <input> <output> <manifest> <certificate-chain> <key>".into());
        }
        let definition = std::fs::read_to_string(&args[3])?;
        let (context, policy) = build_context(args.get(6))?;
        let mut builder =
            c2pa::Builder::from_context(context).with_definition(definition.as_str())?;
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
        let mut signer: Box<dyn c2pa::Signer + Send + Sync> = if args[0] == "sign-pkcs11" {
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
                        stream.write_all(&[1u8])?;
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
        if policy
            .as_ref()
            .is_some_and(|p| p["requireTimestamp"] == true)
        {
            signer = Box::new(TimestampSigner {
                inner: signer,
                socket: args.get(7).ok_or("Missing timestamp bridge")?.clone(),
            });
        }
        builder.sign_file(signer.as_ref(), &args[1], &args[2])?;
        let reader =
            c2pa::Reader::from_context(build_context(args.get(6))?.0).with_file(&args[2])?;
        if reader.validation_state() == c2pa::ValidationState::Invalid
            || (policy.as_ref().is_some_and(|p| p["requireTrusted"] == true)
                && reader.validation_state() != c2pa::ValidationState::Trusted)
        {
            std::fs::remove_file(&args[2])?;
            return Err("Signed output failed validation".into());
        }
        if policy
            .as_ref()
            .is_some_and(|p| p["requireTimestamp"] == true)
            && !timestamp_trusted(&reader)?
        {
            std::fs::remove_file(&args[2])?;
            return Err("Output has no trusted private timestamp".into());
        }
        report(&reader, policy)?;
    } else {
        let (asset, policy_path) = if args.len() == 3 && args[0] == "inspect" {
            (&args[1], Some(&args[2]))
        } else if args.len() == 1 {
            (&args[0], None)
        } else {
            return Err("Usage: c2pa-worker <asset-path> or inspect <asset-path> <policy>".into());
        };
        let (context, policy) = build_context(policy_path)?;
        let reader = c2pa::Reader::from_context(context).with_file(asset)?;
        report(&reader, policy)?;
    }
    Ok(())
}

fn build_context(
    path: Option<&String>,
) -> Result<(c2pa::Context, Option<serde_json::Value>), Box<dyn std::error::Error>> {
    let baseline = serde_json::json!({"core":{"allowed_network_hosts":[],"allow_redirects":false},"verify":{"ocsp_fetch":false,"remote_manifest_fetch":false}});
    let context = c2pa::Context::new().with_settings(baseline)?;
    if let Some(path) = path {
        let policy: serde_json::Value = serde_json::from_slice(&std::fs::read(path)?)?;
        let context = context.with_settings(policy["settings"].clone())?;
        Ok((context, Some(policy)))
    } else {
        Ok((context, None))
    }
}
fn report(
    reader: &c2pa::Reader,
    policy: Option<serde_json::Value>,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut result: serde_json::Value = serde_json::from_str(&reader.json())?;
    if let Some(policy) = policy {
        result["portal_trust_policy"] = serde_json::json!({"versionId":policy["versionId"],"source":policy["source"],"requireTrustedSigning":policy["requireTrusted"],"publicTrustVerified":false,"timestampVersionId":policy["timestampVersionId"],"privateTimestampRequired":policy["requireTimestamp"],"privateTimestampTrusted":timestamp_trusted(reader)?});
    }
    println!("{result}");
    Ok(())
}

fn timestamp_trusted(reader: &c2pa::Reader) -> Result<bool, Box<dyn std::error::Error>> {
    let report: serde_json::Value = serde_json::from_str(&reader.json())?;
    Ok(report["validation_results"]["activeManifest"]["success"]
        .as_array()
        .is_some_and(|codes| codes.iter().any(|c| c["code"] == "timeStamp.trusted")))
}
struct TimestampSigner {
    inner: Box<dyn c2pa::Signer + Send + Sync>,
    socket: String,
}
impl c2pa::Signer for TimestampSigner {
    fn sign(&self, data: &[u8]) -> c2pa::Result<Vec<u8>> {
        self.inner.sign(data)
    }
    fn alg(&self) -> c2pa::SigningAlg {
        self.inner.alg()
    }
    fn certs(&self) -> c2pa::Result<Vec<Vec<u8>>> {
        self.inner.certs()
    }
    fn reserve_size(&self) -> usize {
        self.inner.reserve_size() + 20000
    }
    fn time_authority_url(&self) -> Option<String> {
        Some("urn:c2pa-portal:private-tsa".into())
    }
    fn send_timestamp_request(&self, message: &[u8]) -> Option<c2pa::Result<Vec<u8>>> {
        Some(self.inner.timestamp_request_body(message).and_then(|body| {
            use std::io::{Read, Write};
            let exchange = || -> std::io::Result<Vec<u8>> {
                if body.len() > 65536 {
                    return Err(std::io::Error::other("Timestamp request too large"));
                }
                let mut stream = std::os::unix::net::UnixStream::connect(&self.socket)?;
                stream.set_read_timeout(Some(std::time::Duration::from_secs(25)))?;
                stream.set_write_timeout(Some(std::time::Duration::from_secs(5)))?;
                stream.write_all(&[2u8])?;
                stream.write_all(&(body.len() as u32).to_be_bytes())?;
                stream.write_all(&body)?;
                let mut length = [0u8; 4];
                stream.read_exact(&mut length)?;
                let length = u32::from_be_bytes(length) as usize;
                if length == 0 || length > 65536 {
                    return Err(std::io::Error::other("Invalid timestamp response size"));
                }
                let mut response = vec![0; length];
                stream.read_exact(&mut response)?;
                Ok(response)
            };
            exchange().map_err(c2pa::Error::IoError)
        }))
    }
}
