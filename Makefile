SHELL := /bin/sh

.PHONY: help install-deps dev build check clean tauri-info

help:
	@echo "Targets:"
	@echo "  make install-deps  Install npm dependencies"
	@echo "  make dev           Run Tauri app in development mode"
	@echo "  make build         Build release bundle"
	@echo "  make check         Run frontend and Rust checks"
	@echo "  make clean         Remove build outputs"
	@echo "  make tauri-info    Print Tauri environment info"

install-deps:
	npm install

dev:
	npm run tauri dev

build:
	npm run tauri build

check:
	npm run build
	cd src-tauri && cargo check

clean:
	rm -rf dist node_modules src-tauri/target

tauri-info:
	npx tauri info
