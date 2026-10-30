#!/bin/sh
# 容器启动脚本：以 root 启动时，把数据目录的所有者改成运行用户，再切换成该用户运行服务（程序本身不以 root 运行）。
# 与 Redis / Valkey 等官方镜像的做法相同；在 compose 里写了 user: 时容器不以 root 启动，直接运行（需自行保证数据目录可写）。
#
# PUID / PGID：运行用户的 uid / gid，默认 1000。设成宿主机上自己账号的 uid / gid，映射目录里的文件就属于自己。
set -e

DATA_DIR="${LEGADO_DATA_DIR:-/data}"

if [ "$(id -u)" = '0' ]; then
    PUID="${PUID:-1000}"
    PGID="${PGID:-1000}"
    mkdir -p "$DATA_DIR"
    # 只改所有者不对的文件：以前用 root 运行时写下的数据第一次会改一遍，之后启动几乎不花时间
    find "$DATA_DIR" \( \! -user "$PUID" -o \! -group "$PGID" \) -exec chown "$PUID:$PGID" '{}' +
    export HOME="$DATA_DIR"
    exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups -- "$@"
fi

if [ ! -w "$DATA_DIR" ]; then
    echo >&2 "警告：数据目录 $DATA_DIR 对当前用户（uid $(id -u)）不可写，服务将无法保存数据。"
    echo >&2 "      请修改映射目录的权限，或去掉 compose 里的 user: 让容器自动修改。"
fi
exec "$@"
