SHELL := /bin/sh

.PHONY: help install-deps dev build check test clean tauri-info vendor-adb

help:
	@echo "Targets:"
	@echo "  make install-deps  Install npm dependencies"
	@echo "  make dev           Run Tauri app in development mode"
	@echo "  make build         Build release bundle"
	@echo "  make check         Run frontend and Rust checks"
	@echo "  make test          Run Rust unit tests"
	@echo "  make clean         Remove build outputs"
	@echo "  make tauri-info    Print Tauri environment info"
	@echo "  make vendor-adb    Refresh bundled adb from Android platform-tools"

install-deps:
	npm install

dev:
	npm run tauri dev

build:
	RUSTFLAGS="--remap-path-prefix=$(HOME)=/ --remap-path-prefix=$(CURDIR)=." npm run tauri build

check:
	npm run build
	cd src-tauri && cargo check && cargo clippy --all-targets

test:
	cd src-tauri && cargo test

# Пересобирает вшитые архивы adb из свежих platform-tools. После обновления не забудьте
# поправить ADB_VERSION в src-tauri/src/lib.rs — по нему считается путь распаковки.
vendor-adb:
	@set -e; \
	tmp=$$(mktemp -d); \
	for os in darwin windows linux; do \
		curl -sSL -o $$tmp/$$os.zip https://dl.google.com/android/repository/platform-tools-latest-$$os.zip; \
		unzip -q -o $$tmp/$$os.zip -d $$tmp/$$os; \
	done; \
	rm -f src-tauri/binaries/adb-*.zip; \
	zip -q -9 -j src-tauri/binaries/adb-macos.zip $$tmp/darwin/platform-tools/adb; \
	zip -q -9 -j src-tauri/binaries/adb-linux.zip $$tmp/linux/platform-tools/adb; \
	zip -q -9 -j src-tauri/binaries/adb-windows.zip \
		$$tmp/windows/platform-tools/adb.exe \
		$$tmp/windows/platform-tools/AdbWinApi.dll \
		$$tmp/windows/platform-tools/AdbWinUsbApi.dll \
		$$tmp/windows/platform-tools/libwinpthread-1.dll; \
	$$tmp/darwin/platform-tools/adb --version; \
	rm -rf $$tmp

clean:
	rm -rf dist node_modules src-tauri/target

tauri-info:
	npx tauri info
