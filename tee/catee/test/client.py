#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Small manual client; the enclave, not this client, decides working hours."""
import argparse
import re
import socket


def check_time(path, time_text):
    if not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", time_text):
        raise ValueError("Time must be HH:MM between 00:00 and 23:59")
    hour, minute = map(int, time_text.split(":"))
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(3)
        connection.connect(path)
        connection.sendall(f"CHECK {hour * 60 + minute}\n".encode("ascii"))
        response = bytearray()
        while len(response) < 64:
            ch = connection.recv(1)
            if not ch:
                raise RuntimeError("Service closed before a complete response")
            response.extend(ch)
            if ch == b"\n":
                break
        if response == b"ALLOW HW DEBUG\n":
            return True
        if response == b"DENY HW DEBUG\n":
            return False
        raise RuntimeError(f"Service returned an error or invalid response: {response!r}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("socket_path")
    parser.add_argument("time", help="supplied time, HH:MM (not a trusted clock)")
    args = parser.parse_args()
    try:
        print(f"{args.time} -> {str(check_time(args.socket_path, args.time)).lower()} (HW DEBUG)")
    except (OSError, ValueError, RuntimeError) as error:
        parser.exit(1, f"CATEE client: {error}\n")
