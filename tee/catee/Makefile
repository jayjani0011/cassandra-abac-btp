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

$(BUILD)/Enclave.o: src/Enclave/Enclave.cpp src/Enclave/AdminPrivileges.h src/Enclave/SubnetConfiguration.h $(BUILD)/trusted/Enclave_t.h
	$(CXX) $(TRUSTED_FLAGS) -std=c++11 -nostdinc++ -I$(SGX_SDK)/include/libcxx -c $< -o $@

$(BUILD)/Enclave_u.o: $(BUILD)/untrusted/Enclave_u.c $(BUILD)/untrusted/Enclave_u.h
	$(CC) $(HOST_FLAGS) -fPIC -c $< -o $@

$(BUILD)/catee-service: src/sgx_service.cpp $(BUILD)/Enclave_u.o
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

test: all test-admin test-admin-hw
	python3 test/test_service.py
	python3 test/test_sealing.py

.PHONY: test-admin
$(BUILD)/test-admin-privileges: test/test_admin_privileges.cpp src/Enclave/AdminPrivileges.h
	mkdir -p $(BUILD)
	$(CXX) -std=c++11 -Wall -Wextra -Werror -Isrc/Enclave $< -o $@

test-admin: $(BUILD)/test-admin-privileges
	./$(BUILD)/test-admin-privileges

.PHONY: test-admin-hw
$(BUILD)/test-admin-ecalls: test/test_admin_ecalls.cpp $(BUILD)/Enclave_u.o
	$(CXX) $(HOST_FLAGS) -std=c++11 $^ -L$(SGX_SDK)/lib64 -lsgx_urts -lpthread -o $@

test-admin-hw: $(BUILD)/test-admin-ecalls $(BUILD)/enclave.signed.so
	./$(BUILD)/test-admin-ecalls $(BUILD)/enclave.signed.so

# Preserve the local development key; do not delete persistent material on rebuild.
clean:
	rm -rf $(BUILD)

# Java 11 Unix-domain socket transport; dependency binaries stay under build/.
JUNIXSOCKET_VERSION := 2.11.1
JAVA_JARS := $(BUILD)/java-deps/junixsocket-common-$(JUNIXSOCKET_VERSION).jar \
             $(BUILD)/java-deps/junixsocket-native-common-$(JUNIXSOCKET_VERSION).jar

$(BUILD)/java-deps/junixsocket-common-$(JUNIXSOCKET_VERSION).jar: JAVA_ARTIFACT = junixsocket-common
$(BUILD)/java-deps/junixsocket-common-$(JUNIXSOCKET_VERSION).jar: JAVA_SHA256 = 152680589a359acd73773e3e7d9d9842007b7b86d8f8e0f828e1f87a81ad5d6a
$(BUILD)/java-deps/junixsocket-native-common-$(JUNIXSOCKET_VERSION).jar: JAVA_ARTIFACT = junixsocket-native-common
$(BUILD)/java-deps/junixsocket-native-common-$(JUNIXSOCKET_VERSION).jar: JAVA_SHA256 = 9aa0db38f76b499e36c2b56e7bf5472ceb19ad4d21ebe71b25d1bc81f0336040

$(JAVA_JARS):
	mkdir -p $(BUILD)/java-deps
	curl --fail --location --connect-timeout 10 --max-time 60 --output $@.tmp https://repo.maven.apache.org/maven2/com/kohlschutter/junixsocket/$(JAVA_ARTIFACT)/$(JUNIXSOCKET_VERSION)/$(@F)
	printf '%s  %s\n' '$(JAVA_SHA256)' '$@.tmp' | sha256sum --check -
	mv $@.tmp $@

.PHONY: java-client test-java
java-client: $(JAVA_JARS)
	mkdir -p $(BUILD)/java
	javac --release 11 -cp '$(BUILD)/java-deps/*' -d $(BUILD)/java ../../src/java/org/apache/cassandra/auth/catee/CateeClient.java ../../src/java/org/apache/cassandra/auth/catee/CateeSubnet.java $$(find test/java -name '*.java')

test-java: all java-client
	python3 test/test_java_client.py

# Separate test enclave, signed with the local development key, for v1 migration
# and authenticated-but-invalid payloads. Never linked into the service enclave.
$(BUILD)/fixture:
	mkdir -p $@

$(BUILD)/fixture/Fixture_t.c $(BUILD)/fixture/Fixture_t.h $(BUILD)/fixture/Fixture_u.c $(BUILD)/fixture/Fixture_u.h &: test/sealing_fixture/Fixture.edl | $(BUILD)/fixture
	$(EDGER) --trusted $< --search-path $(SGX_SDK)/include --trusted-dir $(BUILD)/fixture
	$(EDGER) --untrusted $< --search-path $(SGX_SDK)/include --untrusted-dir $(BUILD)/fixture

$(BUILD)/fixture/Fixture_t.o: $(BUILD)/fixture/Fixture_t.c $(BUILD)/fixture/Fixture_t.h
	$(CC) $(TRUSTED_FLAGS) -I$(BUILD)/fixture -c $< -o $@

$(BUILD)/fixture/Fixture.o: test/sealing_fixture/Fixture.cpp $(BUILD)/fixture/Fixture_t.h | $(BUILD)/fixture
	$(CXX) $(TRUSTED_FLAGS) -I$(BUILD)/fixture -std=c++11 -nostdinc++ -I$(SGX_SDK)/include/libcxx -c $< -o $@

$(BUILD)/fixture/Fixture_u.o: $(BUILD)/fixture/Fixture_u.c $(BUILD)/fixture/Fixture_u.h
	$(CC) $(HOST_FLAGS) -I$(BUILD)/fixture -c $< -o $@

$(BUILD)/fixture/enclave.so: $(BUILD)/fixture/Fixture.o $(BUILD)/fixture/Fixture_t.o src/Enclave/Enclave.lds
	$(CXX) -m64 $(filter %.o,$^) -o $@ $(MITIGATION_LDFLAGS) \
	    -Wl,-z,relro,-z,now,-z,noexecstack -nostdlib -nodefaultlibs -nostartfiles \
	    -L$(SGX_TRUSTED_LIBRARY_PATH) -Wl,--no-undefined \
	    -Wl,--whole-archive -lsgx_trts -Wl,--no-whole-archive \
	    -Wl,--start-group -lsgx_tstdc -lsgx_tcrypto -lsgx_tservice -Wl,--end-group \
	    -Wl,-Bstatic,-Bsymbolic,-pie,-eenclave_entry,--export-dynamic \
	    -Wl,--defsym,__ImageBase=0,--gc-sections,--version-script=src/Enclave/Enclave.lds

$(BUILD)/fixture/enclave.signed.so: $(BUILD)/fixture/enclave.so src/Enclave/Enclave.config.xml $(KEY)
	$(SIGNER) sign -key $(KEY) -enclave $< -out $@ -config src/Enclave/Enclave.config.xml

$(BUILD)/fixture/seal-fixture: test/sealing_fixture/seal_fixture.cpp $(BUILD)/fixture/Fixture_u.o
	$(CXX) $(HOST_FLAGS) -I$(BUILD)/fixture -std=c++11 $^ -L$(SGX_SDK)/lib64 -lsgx_urts -lpthread -o $@

.PHONY: sealing-fixture
sealing-fixture: $(BUILD)/fixture/enclave.signed.so $(BUILD)/fixture/seal-fixture

test: sealing-fixture

.PHONY: test-environment-hw
$(BUILD)/test-environment-ecalls: test/test_environment_ecalls.cpp $(BUILD)/Enclave_u.o
	$(CXX) $(HOST_FLAGS) -std=c++11 $^ -L$(SGX_SDK)/lib64 -lsgx_urts -lpthread -o $@

test-environment-hw: $(BUILD)/test-environment-ecalls $(BUILD)/enclave.signed.so
	./$(BUILD)/test-environment-ecalls $(BUILD)/enclave.signed.so

test: test-environment-hw
