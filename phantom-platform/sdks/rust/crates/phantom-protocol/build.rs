use std::{env, path::PathBuf};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR")?).join("../../../../contracts/proto");
    let files: Vec<_> = ["envelope", "common", "message", "sync", "signaling"]
        .into_iter()
        .map(|name| root.join(format!("phantom/v1/{name}.proto")))
        .collect();
    for file in &files {
        println!("cargo:rerun-if-changed={}", file.display());
    }
    println!("cargo:rerun-if-env-changed=PROTOC");
    let mut config = prost_build::Config::new();
    config.btree_map(["."]);
    // 协议调试不得打印包含 UserToken 的认证报文。
    config.skip_debug([".phantom.v1.AuthReq"]);
    config.compile_protos(&files, &[root])?;
    Ok(())
}
