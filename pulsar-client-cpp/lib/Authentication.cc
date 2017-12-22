/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
#include <stdio.h>

#include <pulsar/Authentication.h>
#include <lib/LogUtils.h>

#include <string>
#include <vector>
#include <iostream>
#include <dlfcn.h>
#include <cstdlib>
#include <boost/make_shared.hpp>
#include <boost/thread.hpp>
#include <boost/algorithm/string.hpp>

DECLARE_LOG_OBJECT()

namespace pulsar {

    AuthenticationDataProvider::AuthenticationDataProvider(){

    }

    AuthenticationDataProvider::~AuthenticationDataProvider() {

    }

    bool AuthenticationDataProvider::hasDataForTls() {
        return false;
    }

    std::string AuthenticationDataProvider::getTlsCertificates() {
        return "none";
    }

    std::string AuthenticationDataProvider::getTlsPrivateKey() {
        return "none";
    }

    bool AuthenticationDataProvider::hasDataForHttp() {
        return false;
    }

    std::string AuthenticationDataProvider::getHttpAuthType() {
        return "none";
    }

    std::string AuthenticationDataProvider::getHttpHeaders() {
        return "none";
    }

    bool AuthenticationDataProvider::hasDataFromCommand(){
        return false;
    }

    std::string AuthenticationDataProvider::getCommandData() {
        return "none";
    }


    Authentication::Authentication() {
    }

    Authentication::~Authentication() {
    }

    class AuthDisabledData : public AuthenticationDataProvider {
    public:
        AuthDisabledData(ParamMap& params){
        }
    };

    class AuthDisabled : public Authentication {
    public:
        AuthDisabled(AuthenticationDataPtr& authData) {
            authData_ = authData;
        }

        static AuthenticationPtr create(ParamMap& params) {
            AuthenticationDataPtr authData = AuthenticationDataPtr(new AuthDisabledData(params));
            return AuthenticationPtr(new AuthDisabled(authData));
        }

        const std::string getAuthMethodName() const {
            return "none";
        }
    };


    AuthenticationPtr AuthFactory::Disabled() {
        ParamMap params;
        return AuthDisabled::create(params);
    }

    AuthenticationPtr AuthFactory::create(const std::string& dynamicLibPath) {
        ParamMap params;
        return AuthFactory::create(dynamicLibPath, params);
    }

    boost::mutex mutex;
    std::vector<void*> AuthFactory::loadedLibrariesHandles_;
    bool AuthFactory::isShutdownHookRegistered_ = false;

    void AuthFactory::release_handles() {
        boost::lock_guard<boost::mutex> lock(mutex);
        for (std::vector<void*>::iterator ite = AuthFactory::loadedLibrariesHandles_.begin(); ite != AuthFactory::loadedLibrariesHandles_.end();
             ite++) {
            dlclose(*ite);
        }
        loadedLibrariesHandles_.clear();
    }

    AuthenticationPtr AuthFactory::create(const std::string& dynamicLibPath, const std::string& authParamsString) {
        {
            boost::lock_guard<boost::mutex> lock(mutex);
            if (!AuthFactory::isShutdownHookRegistered_) {
                atexit(release_handles);
                AuthFactory::isShutdownHookRegistered_ = true;
            }
        }
        Authentication *auth = NULL;
        void *handle = dlopen(dynamicLibPath.c_str(), RTLD_LAZY);
        if (handle != NULL) {
            {
                boost::lock_guard<boost::mutex> lock(mutex);
                loadedLibrariesHandles_.push_back(handle);
            }
            Authentication *(*createAuthentication)(const std::string&);
            *(void **) (&createAuthentication) = dlsym(handle, "create");
            if (createAuthentication != NULL) {
                auth = createAuthentication(authParamsString);
            } else {
                ParamMap paramMap;
                if(!authParamsString.empty()) {
                    std::vector<std::string> params;
                    boost::algorithm::split(params, authParamsString, boost::is_any_of(","));
                    for(int i = 0; i<params.size(); i++) {
                        std::vector<std::string> kv;
                        boost::algorithm::split(kv, params[i], boost::is_any_of(":"));
                        if (kv.size() == 2) {
                            paramMap[kv[0]] = kv[1];
                        }
                    }
                }
                return AuthFactory::create(dynamicLibPath, paramMap);
            }
        }
        return AuthenticationPtr(auth);
    }

    AuthenticationPtr AuthFactory::create(const std::string& dynamicLibPath, ParamMap& params) {
        {
            boost::lock_guard<boost::mutex> lock(mutex);
            if (!AuthFactory::isShutdownHookRegistered_) {
                atexit(release_handles);
                AuthFactory::isShutdownHookRegistered_ = true;
            }
        }
        Authentication *auth = NULL;
        void *handle = dlopen(dynamicLibPath.c_str(), RTLD_LAZY);
        if (handle != NULL) {
            boost::lock_guard<boost::mutex> lock(mutex);
            loadedLibrariesHandles_.push_back(handle);
            Authentication *(*createAuthentication)(ParamMap&);
            *(void **) (&createAuthentication) = dlsym(handle, "createFromMap");
            if (createAuthentication != NULL) {
                auth = createAuthentication(params);
            }
        }
        return AuthenticationPtr(auth);
    }

}
