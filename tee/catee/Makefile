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

SGX_SDK ?= $(HOME)/sgxsdk
SGX_MODE ?= HW
SGX_DEBUG ?= 1
ifneq ($(SGX_MODE),HW)
$(error This first slice supports SGX_MODE=HW only)
endif
ifneq ($(SGX_DEBUG),1)
$(error This first slice supports SGX_DEBUG=1 only; do not label it production)
endif
include $(SGX_SDK)/buildenv.mk

.DEFAULT_GOAL := all
BUILD := build
KEY := .local/Enclave_private.pem
EDGER := $(SGX_SDK)/bin/x64/sgx_edger8r
SIGNER := $(SGX_SDK)/bin/x64/sgx_sign
HOST_FLAGS := -m64 -O2 -g -Wall -Wextra -I$(SGX_SDK)/include -I$(BUILD)/untrusted
TRUSTED_FLAGS := -m64 -O2 -g -Wall -Wextra -nostdinc -fvisibility=hidden -fpie \
    -fstack-protector-strong -ffunction-sections -fdata-sections $(MITIGATION_CFLAGS) \
    -I$(SGX_SDK)/include -I$(SGX_SDK)/include/tlibc -I$(BUILD)/trusted

.PHONY: all test clean
all: $(BUILD)/catee-service $(BUILD)/enclave.signed.so

$(BUILD)/trusted $(BUILD)/untrusted:
	mkdir -p $@

# Grouped targets ensure parallel make invokes each generator only once.
$(BUILD)/trusted/Enclave_t.c $(BUILD)/trusted/Enclave_t.h &: src/Enclave/Enclave.edl | $(BUILD)/trusted
	$(EDGER) --trusted $< --search-path $(SGX_SDK)/include --trusted-dir $(BUILD)/trusted

$(BUILD)/untrusted/Enclave_u.c $(BUILD)/untrusted/Enclave_u.h &: src/Enclave/Enclave.edl | $(BUILD)/untrusted
	$(EDGER) --untrusted $< --search-path $(SGX_SDK)/include --untrusted-dir $(BUILD)/untrusted

$(BUILD)/Enclave_t.o: $(BUILD)/trusted/Enclave_t.c $(BUILD)/trusted/Enclave_t.h
	$(CC) $(TRUSTED_FLAGS) -c $< -o $@

$(BUILD)/Enclave.o: src/Enclave/Enclave.cpp $(BUILD)/trusted/Enclave_t.h
	$(CXX) $(TRUSTED_FLAGS) -std=c++11 -nostdinc++ -I$(SGX_SDK)/include/libcxx -c $< -o $@

$(BUILD)/Enclave_u.o: $(BUILD)/untrusted/Enclave_u.c $(BUILD)/untrusted/Enclave_u.h
	$(CC) $(HOST_FLAGS) -fPIC -c $< -o $@

$(BUILD)/catee-service: src/service.cpp $(BUILD)/Enclave_u.o
	$(CXX) $(HOST_FLAGS) -std=c++17 $^ -L$(SGX_SDK)/lib64 -lsgx_urts -lpthread -o $@

$(BUILD)/enclave.so: $(BUILD)/Enclave.o $(BUILD)/Enclave_t.o src/Enclave/Enclave.lds
	$(CXX) -m64 $(filter %.o,$^) -o $@ $(MITIGATION_LDFLAGS) \
	    -Wl,-z,relro,-z,now,-z,noexecstack -nostdlib -nodefaultlibs -nostartfiles \
	    -L$(SGX_TRUSTED_LIBRARY_PATH) -Wl,--no-undefined \
	    -Wl,--whole-archive -lsgx_trts -Wl,--no-whole-archive \
	    -Wl,--start-group -lsgx_tstdc -lsgx_tcrypto -lsgx_tservice -Wl,--end-group \
	    -Wl,-Bstatic,-Bsymbolic,-pie,-eenclave_entry,--export-dynamic \
	    -Wl,--defsym,__ImageBase=0,--gc-sections,--version-script=src/Enclave/Enclave.lds

$(KEY):
	mkdir -p .local
	chmod 700 .local
	umask 077; openssl genrsa -3 -out $@ 3072

$(BUILD)/enclave.signed.so: $(BUILD)/enclave.so src/Enclave/Enclave.config.xml $(KEY)
	$(SIGNER) sign -key $(KEY) -enclave $< -out $@ -config src/Enclave/Enclave.config.xml

test: all
	python3 test/test_service.py

# Preserve the local development key; do not delete persistent material on rebuild.
clean:
	rm -rf $(BUILD)
