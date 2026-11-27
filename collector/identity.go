package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

type deviceInfo struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type identityFile struct {
	deviceInfo
	Clients []string `json:"clients"`
	PEMCert string   `json:"cert"`
	PEMKey  string   `json:"key"`
	Pin     string   `json:"pin"`
}

type identity struct {
	path       string
	file       identityFile
	cert       tls.Certificate
	authorized map[string]struct{}
}

func createIdentity() (identityFile, error) {
	var file identityFile
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return file, err
	}
	id[6] = id[6]&0x0f | 0x40
	id[8] = id[8]&0x3f | 0x80
	name, err := os.Hostname()
	if err != nil {
		return file, err
	}
	file.deviceInfo = deviceInfo{
		ID:   fmt.Sprintf("%x-%x-%x-%x-%x", id[:4], id[4:6], id[6:8], id[8:10], id[10:]),
		Name: name,
	}
	file.Clients = []string{}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return file, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return file, err
	}
	serial.SetBit(serial, 128, 1)
	now := time.Now()
	template := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "Codex Status Screen"},
		NotBefore:    now.Add(-48 * time.Hour),
		NotAfter:     now.AddDate(5, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	cert, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return file, err
	}
	file.PEMCert = string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert}))
	file.PEMKey = string(pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}))
	file.Pin = digest(cert)
	return file, nil
}

func openIdentity(home string) (*identity, error) {
	path := filepath.Join(home, "identity.json")
	var file identityFile
	raw, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := json.Unmarshal(raw, &file); err != nil {
			return nil, err
		}
	case errors.Is(err, os.ErrNotExist):
		if err := os.MkdirAll(home, 0700); err != nil {
			return nil, err
		}
		file, err = createIdentity()
		if err != nil {
			return nil, err
		}
		if err := saveIdentity(path, file); err != nil {
			return nil, err
		}
	default:
		return nil, err
	}

	cert, err := tls.X509KeyPair([]byte(file.PEMCert), []byte(file.PEMKey))
	if err != nil {
		return nil, err
	}
	if file.ID == "" || file.Name == "" || file.Pin != digest(cert.Certificate[0]) {
		return nil, errors.New("配对记录或证书指纹不匹配")
	}
	authorized := make(map[string]struct{}, len(file.Clients))
	for _, client := range file.Clients {
		authorized[client] = struct{}{}
	}
	return &identity{path: path, file: file, cert: cert, authorized: authorized}, nil
}

func saveIdentity(path string, file identityFile) error {
	draft, err := os.CreateTemp(filepath.Dir(path), "identity-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(draft.Name())
	writeErr := json.NewEncoder(draft).Encode(file)
	closeErr := draft.Close()
	if err := errors.Join(writeErr, closeErr); err != nil {
		return err
	}
	return os.Rename(draft.Name(), path)
}

func digest(value []byte) string {
	sum := sha256.Sum256(value)
	return hex.EncodeToString(sum[:])
}

func (id *identity) pair() (string, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	token := base64.RawURLEncoding.EncodeToString(raw[:])
	hash := digest([]byte(token))
	next := id.file
	next.Clients = append(next.Clients, hash)
	if err := saveIdentity(id.path, next); err != nil {
		return "", err
	}
	id.file.Clients = next.Clients
	id.authorized[hash] = struct{}{}
	return token, nil
}
