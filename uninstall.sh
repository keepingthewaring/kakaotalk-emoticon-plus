#!/system/bin/sh
for d in /data/user/*/com.kakao.talk; do
    rm -rf "$d/files/emoplus"
done
