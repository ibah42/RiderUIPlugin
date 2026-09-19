// Rust is a lighter pass than the iOS languages: the dialect exists mainly so a lifetime is
// not read as a character literal, which used to swallow whole declarations.

use std::fmt;

pub mod net {
    pub struct Client<'a> {
        name: &'a str,
        retries: u32,
    }

    pub trait Fetch {
        fn get(&self) -> String;
    }

    impl<'a> Client<'a> {
        pub fn new(name: &'a str) -> Client<'a> {
            Client { name, retries: 0 }
        }

        pub fn longest(x: &'a str, y: &'a str) -> &'a str {
            if x.len() > y.len() {
                x
            } else {
                y
            }
        }
    }

    impl<'a> Fetch for Client<'a> {
        fn get(&self) -> String {
            let raw = r#"a "quoted" { brace"#;
            let tick = 'x';
            return format!("{}{}", raw, tick);
        }
    }

    pub enum Outcome {
        Ok,
        Failed,
    }
}

unsafe fn danger() {
    let marker = 'z';
    println!("{}", marker);
}
