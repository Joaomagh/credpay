ARG SOURCE_IMAGE
FROM ${SOURCE_IMAGE}
# Uma única invocação remove também suas próprias ferramentas ao terminar.
# Diretórios literais da imagem; nenhuma montagem/workspace/host é envolvida.
RUN --network=none set -eu; \
    set -- /bin /sbin /lib /lib64 /usr /var /root /home /run /tmp /opt /srv /media /mnt /boot; \
    for path in /etc/* /etc/.[!.]* /etc/..?*; do \
      case "$path" in /etc/hosts|/etc/hostname|/etc/resolv.conf) continue;; esac; \
      if [ -e "$path" ] || [ -L "$path" ]; then set -- "$@" "$path"; fi; \
    done; exec /usr/bin/rm -rf -- "$@"
ADD rootfs.tar /
ENV PATH=/usr/bin HOME=/tmp LANG=C LC_ALL=C
WORKDIR /workspace
USER 10001:10001
ENTRYPOINT ["/usr/bin/sh"]
CMD ["-c", "exit 0"]
