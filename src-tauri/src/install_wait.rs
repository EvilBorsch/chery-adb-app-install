use std::time::{Duration, Instant};

pub(crate) fn wait_for_installation<E>(
    timeout: Duration,
    poll_interval: Duration,
    mut installed_package: impl FnMut() -> Result<Option<String>, E>,
) -> Result<Option<String>, E> {
    let started = Instant::now();
    loop {
        if let Some(package) = installed_package()? {
            return Ok(Some(package));
        }
        let remaining = timeout.saturating_sub(started.elapsed());
        if remaining.is_zero() {
            return Ok(None);
        }
        std::thread::sleep(poll_interval.min(remaining));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    #[test]
    fn installed_package_returns_without_waiting() {
        let package = wait_for_installation(Duration::ZERO, Duration::from_secs(2), || {
            Ok::<_, ()>(Some("ru.yandex.yandexnavi".to_string()))
        })
        .unwrap();

        assert_eq!(package.as_deref(), Some("ru.yandex.yandexnavi"));
    }

    #[test]
    fn package_appearing_after_several_checks_is_found() {
        let mut checks = 0;
        let package =
            wait_for_installation(Duration::from_secs(5), Duration::from_millis(1), || {
                checks += 1;
                Ok::<_, ()>((checks == 4).then(|| "ru.yandex.yandexnavi".to_string()))
            })
            .unwrap();

        assert_eq!(package.as_deref(), Some("ru.yandex.yandexnavi"));
        assert_eq!(checks, 4);
    }

    #[test]
    fn missing_package_waits_until_timeout() {
        let timeout = Duration::from_millis(20);
        let started = Instant::now();
        let package =
            wait_for_installation(timeout, Duration::from_millis(2), || Ok::<_, ()>(None)).unwrap();

        assert_eq!(package, None);
        assert!(started.elapsed() >= timeout);
    }

    #[test]
    fn zero_timeout_checks_once_then_stops() {
        let mut checks = 0;
        let package = wait_for_installation(Duration::ZERO, Duration::from_secs(2), || {
            checks += 1;
            Ok::<_, ()>(None)
        })
        .unwrap();

        assert_eq!(package, None);
        assert_eq!(checks, 1);
    }

    #[test]
    fn device_error_is_returned_without_retrying() {
        let mut checks = 0;
        let result = wait_for_installation(Duration::from_secs(5), Duration::from_secs(2), || {
            checks += 1;
            Err("device disconnected")
        });

        assert_eq!(result, Err("device disconnected"));
        assert_eq!(checks, 1);
    }
}
