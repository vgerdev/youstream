#!/usr/bin/env bash
# One-time setup of an Ubuntu WSL2 distribution for the local cluster: NFS client, Docker, k3s (Kubernetes 1.34,
# like DOKS), Helm, a kubeconfig for your user, and unprivileged ports from 80 so forward.sh needs no sudo.
# Run inside WSL as your normal user (it uses sudo):  deploy/local/setup-wsl.sh
set -euo pipefail

K3S_CHANNEL="${K3S_CHANNEL:-v1.34}"
HELM_VERSION="${HELM_VERSION:-v3.19.0}"

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

grep -qi microsoft /proc/version || die "run this inside WSL"
[ -d /run/systemd/system ] || die "systemd is off: add [boot] systemd=true to /etc/wsl.conf, run 'wsl --shutdown' in Windows, reopen WSL"

step "Packages: NFS client (kubelet mounts the NFS volume), Docker with BuildKit, tools"
sudo apt-get update -q
sudo apt-get install -y -q nfs-common docker.io docker-buildx curl ca-certificates openssh-client
sudo systemctl enable --now docker
if ! id -nG | grep -qw docker; then
    sudo usermod -aG docker "$USER"
    echo "added $USER to group docker (takes effect in a new WSL shell)"
fi

step "Ports from 80 without root (kubectl port-forward on 80/443)"
echo 'net.ipv4.ip_unprivileged_port_start=80' | sudo tee /etc/sysctl.d/90-youstream-ports.conf >/dev/null
sudo sysctl -q --system

if command -v k3s >/dev/null; then
    step "k3s is installed: $(k3s --version | head -1)"
else
    step "k3s ($K3S_CHANNEL channel) without Traefik and ServiceLB (the platform installs Envoy Gateway)"
    curl -sfL https://get.k3s.io | INSTALL_K3S_CHANNEL="$K3S_CHANNEL" sh -s - \
        --disable traefik --disable servicelb --write-kubeconfig-mode 600
fi

step "kubeconfig for $USER (context: default)"
mkdir -p "$HOME/.kube"
sudo k3s kubectl config view --raw >"$HOME/.kube/config.k3s"
chmod 600 "$HOME/.kube/config.k3s"
if [ -f "$HOME/.kube/config" ] && ! cmp -s "$HOME/.kube/config" "$HOME/.kube/config.k3s"; then
    kubeconfig="$HOME/.kube/config.k3s"
    echo "kept your existing ~/.kube/config; the k3s one is ~/.kube/config.k3s"
else
    mv "$HOME/.kube/config.k3s" "$HOME/.kube/config"
    kubeconfig="$HOME/.kube/config"
fi
# k3s's kubectl otherwise reads /etc/rancher/k3s/k3s.yaml, which only root may read
export KUBECONFIG="$kubeconfig"
if ! grep -q 'KUBECONFIG=' "$HOME/.bashrc" 2>/dev/null; then
    echo "export KUBECONFIG=$kubeconfig" >>"$HOME/.bashrc"
    echo "added KUBECONFIG to ~/.bashrc"
fi

if command -v helm >/dev/null; then
    step "helm is installed: $(helm version --short)"
else
    step "Helm $HELM_VERSION"
    tmp=$(mktemp -d)
    archive="helm-$HELM_VERSION-linux-amd64.tar.gz"
    curl -fsSL -o "$tmp/$archive" "https://get.helm.sh/$archive"
    curl -fsSL -o "$tmp/$archive.sha256sum" "https://get.helm.sh/$archive.sha256sum"
    (cd "$tmp" && sha256sum -c "$archive.sha256sum")
    tar -xzf "$tmp/$archive" -C "$tmp"
    sudo install -m 0755 "$tmp/linux-amd64/helm" /usr/local/bin/helm
    rm -rf "$tmp"
fi

if ! command -v doctl >/dev/null && command -v doctl.exe >/dev/null; then
    step "doctl: using the Windows doctl.exe (and its sign-in) from WSL"
    sudo ln -sf "$(command -v doctl.exe)" /usr/local/bin/doctl
fi

step "Cluster"
kubectl get nodes -o wide
echo
echo "Next: deploy/local/images.sh, then deploy/local/platform.sh (see deploy/local/README.md)"
