#!/usr/bin/env python

"""
 /*
 * Copyright (C) 2016 Jones Chi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""

from subprocess import Popen, PIPE, STDOUT
import socket
import struct
from datetime import datetime

PORT = 53516
bufferSize = 1024
total = 0
start = datetime.now()
SAVE_TO_FILE = True
MONITOR = False
def connect_to_server():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_address = ('localhost', PORT)
    print 'Connecting to %s port %s' % server_address
    sock.connect(server_address)
    # Send data
    while True:
        name = raw_input("put command: ")
        if name == "pause":
            sock.close()
            break
        else:
            print 'Send command: ', name
            sock.send(name+'\n')
            print 'Recv: '
            msg = sock.recv(100)
            print msg


if __name__ == "__main__":
    connect_to_server()
