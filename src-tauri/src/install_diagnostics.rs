fn field<'a>(output: &'a str, prefix: &str) -> Option<&'a str> {
    output
        .lines()
        .rev()
        .find_map(|line| line.strip_prefix(prefix))
}

pub(crate) fn helper_succeeded(output: &str) -> bool {
    field(output, "LOCALINSTALL_STATUS=") == Some("0")
}

pub(crate) fn helper_package(output: &str) -> Option<&str> {
    field(output, "LOCALINSTALL_PACKAGE=").filter(|package| !package.is_empty())
}

pub(crate) fn failure_summary(
    output: &str,
    exit_code: Option<i32>,
    timeout_seconds: u64,
) -> String {
    let stage = field(output, "LOCALINSTALL_STAGE=").unwrap_or("запуск helper");
    if field(output, "LOCALINSTALL_TIMEOUT=").is_some() {
        return format!(
            "Android не вернул результат установки за {timeout_seconds} секунд. \
             Этап: {stage}. Это тайм-аут ожидания; установка на ГУ ещё может продолжаться."
        );
    }
    if let Some(status) = field(output, "LOCALINSTALL_STATUS=") {
        let legacy = field(output, "LOCALINSTALL_LEGACY_STATUS=").unwrap_or("не передан");
        let message = field(output, "LOCALINSTALL_MESSAGE=")
            .filter(|text| !text.is_empty())
            .unwrap_or("Android не передал текстовое описание ошибки");
        let description = match status {
            "-1" => "Требуется подтверждение установки на экране ГУ",
            "2" => "Установка запрещена системой или политикой устройства",
            "3" => "Установка отменена",
            "4" => "Android отклонил APK как некорректный",
            "5" => "Конфликт с установленным приложением или его подписью",
            "6" => "Ошибка хранилища: проверьте свободное место и доступность памяти",
            "7" => "APK несовместим с устройством",
            _ => "Android сообщил об ошибке установки",
        };
        return format!(
            "{description}.\n{message}\nКод Android: PackageInstaller={status}, legacy={legacy}. Этап: {stage}."
        );
    }
    if let Some(exception) = field(output, "LOCALINSTALL_EXCEPTION=") {
        return format!("Ошибка helper на этапе {stage}: {exception}");
    }
    let exit = exit_code.map_or_else(|| "процесс прерван".to_string(), |code| code.to_string());
    format!(
        "Helper завершился без результата PackageInstaller. Код выхода: {exit}. \
         Последний этап: {stage}. Исходный вывод и диагностика приведены ниже."
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn android_failure_message_and_codes_are_preserved() {
        let output = "LOCALINSTALL_STAGE=wait\nLOCALINSTALL_STATUS=6\nLOCALINSTALL_LEGACY_STATUS=-4\nLOCALINSTALL_MESSAGE=INSTALL_FAILED_INSUFFICIENT_STORAGE: Not enough space\n";
        let message = failure_summary(output, Some(1), 120);
        assert!(message.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE: Not enough space"));
        assert!(message.contains("PackageInstaller=6"));
        assert!(message.contains("legacy=-4"));
        assert!(!helper_succeeded(output));
    }

    #[test]
    fn callback_timeout_is_distinct_from_android_rejection() {
        let message = failure_summary(
            "LOCALINSTALL_STAGE=wait\nLOCALINSTALL_TIMEOUT=120\n",
            Some(2),
            120,
        );
        assert!(message.contains("120"));
        assert!(message.contains("не вернул результат"));
        assert!(!message.contains("неизвестн"));
    }

    #[test]
    fn helper_exception_keeps_stage_and_exception_details() {
        let message = failure_summary(
            "LOCALINSTALL_STAGE=create-session\nLOCALINSTALL_EXCEPTION=java.lang.SecurityException: Permission denied\n",
            Some(1),
            120,
        );
        assert!(message.contains("create-session"));
        assert!(message.contains("java.lang.SecurityException: Permission denied"));
    }

    #[test]
    fn missing_callback_reports_process_exit_instead_of_guessing() {
        let message = failure_summary("LOCALINSTALL_STAGE=commit\n", Some(137), 120);
        assert!(message.contains("137"));
        assert!(message.contains("commit"));
        assert!(!message.contains("неизвестн"));
        assert!(!helper_succeeded("LOCALINSTALL_STAGE=commit\n"));
    }

    #[test]
    fn success_requires_explicit_success_status() {
        assert!(helper_succeeded(
            "LOCALINSTALL_STATUS=0\nLOCALINSTALL_MESSAGE=Success\n"
        ));
        assert!(!helper_succeeded("LOCALINSTALL_STATUS=10\n"));
        assert!(!helper_succeeded(""));
    }

    #[test]
    fn android_status_without_message_is_still_reported() {
        let message = failure_summary("LOCALINSTALL_STAGE=wait\nLOCALINSTALL_STATUS=5\nLOCALINSTALL_LEGACY_STATUS=-7\nLOCALINSTALL_MESSAGE=\n", Some(1), 120);
        assert!(message.contains("PackageInstaller=5"));
        assert!(message.contains("legacy=-7"));
        assert!(!message.contains("без результата"));
    }

    #[test]
    fn callback_package_name_is_available_without_manifest_parsing() {
        assert_eq!(
            helper_package("LOCALINSTALL_STATUS=0\nLOCALINSTALL_PACKAGE=ru.yandex.yandexnavi\n"),
            Some("ru.yandex.yandexnavi")
        );
        assert_eq!(helper_package("LOCALINSTALL_PACKAGE=\n"), None);
    }
}
