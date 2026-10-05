#!/usr/bin/env bash
# Client certificates for Wiggins' client-certificate auth mode (mTLS at the
# reverse proxy). Keep the output directory out of git and the CA key offline.
#
#   tools/mtls/client-cert.sh ca DIR           make DIR/ca.key and DIR/ca.crt (give ca.crt to the proxy)
#   tools/mtls/client-cert.sh device DIR NAME  make DIR/NAME.p12 signed by the CA, for the phone
#
# The phone imports NAME.p12 through Wiggins' settings (the system installer asks
# for the export password this script prints). Revoke a device by removing trust
# in its certificate at the proxy, or by replacing the CA.
set -euo pipefail

cmd=${1:?usage: $0 ca DIR | device DIR NAME}
dir=${2:?missing DIR}
mkdir -p "$dir"
umask 077

case "$cmd" in
ca)
    [ -e "$dir/ca.key" ] && { echo "$dir/ca.key exists; not overwriting" >&2; exit 1; }
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
        -keyout "$dir/ca.key" -out "$dir/ca.crt" -days 3650 -subj "/CN=Wiggins client CA" \
        -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign"
    chmod 644 "$dir/ca.crt"
    echo "CA: $dir/ca.crt (public, for the proxy); $dir/ca.key (secret)"
    ;;
device)
    name=${3:?missing NAME}
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
        -keyout "$dir/$name.key" -out "$dir/$name.csr" -subj "/CN=$name"
    openssl x509 -req -in "$dir/$name.csr" -CA "$dir/ca.crt" -CAkey "$dir/ca.key" -CAcreateserial \
        -out "$dir/$name.crt" -days 825 \
        -extfile <(printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n')
    pass=$(openssl rand -base64 18)
    # AES-256 and a SHA-256 MAC rather than openssl's legacy defaults; Android 12+ imports these.
    openssl pkcs12 -export -inkey "$dir/$name.key" -in "$dir/$name.crt" -certfile "$dir/ca.crt" \
        -name "$name" -out "$dir/$name.p12" -passout "pass:$pass" \
        -keypbe AES-256-CBC -certpbe AES-256-CBC -macalg sha256
    rm "$dir/$name.csr" "$dir/$name.key"
    echo "Device: $dir/$name.p12, export password: $pass"
    echo "SHA-256 fingerprint: $(openssl x509 -in "$dir/$name.crt" -noout -fingerprint -sha256 | cut -d= -f2)"
    ;;
*)
    echo "usage: $0 ca DIR | device DIR NAME" >&2; exit 2 ;;
esac
