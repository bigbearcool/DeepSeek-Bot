import assert from 'node:assert/strict'
import { test } from 'node:test'
import { lanAddresses, lanUrl } from '../src/lan.js'

const ifaces = {
  lo0: [{ family: 'IPv4', address: '127.0.0.1', internal: true }],
  docker0: [{ family: 'IPv4', address: '172.17.0.1', internal: false }],
  'br-abc': [{ family: 'IPv4', address: '172.18.0.1', internal: false }],
  utun3: [{ family: 'IPv4', address: '10.8.0.2', internal: false }],
  en1: [{ family: 'IPv4', address: '10.0.0.9', internal: false }],
  en0: [
    { family: 'IPv6', address: 'fe80::1', internal: false },
    { family: 'IPv4', address: '169.254.3.3', internal: false },
    { family: 'IPv4', address: '192.168.1.23', internal: false },
  ],
}

test('lanAddresses skips loopback/docker/virtual and prefers 192.168', () => {
  assert.deepEqual(lanAddresses(ifaces), ['192.168.1.23', '10.0.0.9'])
})

test('lanUrl: wildcard host → detected IP; loopback host → empty; explicit host kept', () => {
  assert.equal(lanUrl({ host: '0.0.0.0', port: 3081, ifaces }), 'http://192.168.1.23:3081')
  assert.equal(lanUrl({ host: '127.0.0.1', port: 3081, ifaces }), '')
  assert.equal(lanUrl({ host: '192.168.9.9', port: 4000, ifaces }), 'http://192.168.9.9:4000')
  assert.equal(lanUrl({ host: '0.0.0.0', port: 3081, ifaces: {} }), '')
})
