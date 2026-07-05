use libloading::Library;
fn main() {
    unsafe {
        let lib = Library::new(r"d:\chunkup-template-1.20.1\build\native\chunkup_cuda.dll").expect("load");
        let f: libloading::Symbol<unsafe extern "C" fn() -> i32> = lib.get(b"chunkup_cuda_is_available\0").expect("sym");
        println!("cuda available: {}", f());
    }
}
