## JEmuleServer 1.2.7

- Add remote administration access support for both CLI TCP console and Web administration panel.
- Implement application-level filtering (`adminAllowedIps`) supporting single IPv4/IPv6 addresses, wildcards (`*`), and CIDR subnet blocks.
- Implement layer-2 MAC address filtering (`adminAllowedMacs`) based on local interface and ARP resolution.
- Add configurable administration bind address (`adminBindAddress`).
- Secure Web admin panel against DNS rebinding and cross-site scripting/origin attacks.
