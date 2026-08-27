const fs = require('fs');
const path = require('path');
const selfsigned = require('selfsigned');

const sslDir = path.join(__dirname, 'ssl');
if (!fs.existsSync(sslDir)) {
  fs.mkdirSync(sslDir, { recursive: true });
}

console.log('[CertGen] Generating self-signed SSL certificates for localhost...');

const attrs = [
  { name: 'commonName', value: 'localhost' }
];

selfsigned.generate(attrs, {
  algorithm: 'sha256',
  days: 365,
  keySize: 2048,
  extensions: [
    {
      name: 'basicConstraints',
      cA: true
    },
    {
      name: 'keyUsage',
      keyCertSign: true,
      digitalSignature: true,
      nonRepudiation: true,
      keyEncipherment: true,
      dataEncipherment: true
    },
    {
      name: 'subjectAltName',
      altNames: [
        {
          type: 2, // DNS
          value: 'localhost'
        },
        {
          type: 7, // IP
          ip: '127.0.0.1'
        }
      ]
    }
  ]
})
.then((pems) => {
  fs.writeFileSync(path.join(sslDir, 'cert.pem'), pems.cert);
  fs.writeFileSync(path.join(sslDir, 'key.pem'), pems.private);

  console.log('[CertGen] SSL Certificates generated successfully under:');
  console.log(`  - ${path.join(sslDir, 'cert.pem')}`);
  console.log(`  - ${path.join(sslDir, 'key.pem')}`);
})
.catch((err) => {
  console.error('[CertGen] Failed to generate certificates:', err);
  process.exit(1);
});
