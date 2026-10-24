"""Launch the configured native ASR companion inside its managed systemd unit."""
import os


def main():
    binary = os.environ["FRIDAY_VOICE_ASR_BINARY"]
    config = os.environ["FRIDAY_VOICE_ASR_CONFIG"]
    os.execv(binary, [binary, "--config", config])


if __name__ == "__main__":
    main()
