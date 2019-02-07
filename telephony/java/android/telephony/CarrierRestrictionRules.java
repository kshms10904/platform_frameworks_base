/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.carrier.CarrierIdentifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains the list of carrier restrictions.
 * Allowed list: it indicates the list of carriers that are allowed.
 * Excluded list: it indicates the list of carriers that are excluded.
 * Default carrier restriction: it indicates the default behavior and the priority between the two
 * lists:
 *  - not allowed: the device only allows usage of carriers that are present in the allowed list
 *    and not present in the excluded list. This implies that if a carrier is not present in either
 *    list, it is not allowed.
 *  - allowed: the device allows all carriers, except those present in the excluded list and not
 *    present in the allowed list. This implies that if a carrier is not present in either list,
 *    it is allowed.
 * MultiSim policy: it indicates the behavior in case of devices with two or more SIM cards.
 *  - MULTISIM_POLICY_NONE: the same configuration is applied to all SIM slots independently. This
 *    is the default value if none is set.
 *  - MULTISIM_POLICY_ONE_VALID_SIM_MUST_BE_PRESENT: it indicates that any SIM card can be used
 *    as far as one SIM card matching the configuration is present in the device.
 *
 * Both lists support the character '?' as wild character. For example, an entry indicating
 * MCC=310 and MNC=??? will match all networks with MCC=310.
 *
 * Example 1: Allowed list contains MCC and MNC of operator A. Excluded list contains operator B,
 *            which has same MCC and MNC, but also GID1 value. The priority allowed list is set
 *            to true. Only SIM cards of operator A are allowed, but not those of B or any other
 *            operator.
 * Example 2: Allowed list contains MCC and MNC of operator A. Excluded list contains an entry
 *            with same MCC, and '???' as MNC. The priority allowed list is set to false.
 *            SIM cards of operator A and all SIM cards with a different MCC value are allowed.
 *            SIM cards of operators with same MCC value and different MNC are not allowed.
 * @hide
 */
@SystemApi
public final class CarrierRestrictionRules implements Parcelable {
    /**
     * The device only allows usage of carriers that are present in the allowed list and not
     * present in the excluded list. This implies that if a carrier is not present in either list,
     * it is not allowed.
     */
    public static final int CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED = 0;

    /**
     * The device allows all carriers, except those present in the excluded list and not present
     * in the allowed list. This implies that if a carrier is not present in either list, it is
     * allowed.
     */
    public static final int CARRIER_RESTRICTION_DEFAULT_ALLOWED = 1;

    /** The same configuration is applied to all SIM slots independently. */
    public static final int MULTISIM_POLICY_NONE = 0;

    /** Any SIM card can be used as far as one SIM card matching the configuration is present. */
    public static final int MULTISIM_POLICY_ONE_VALID_SIM_MUST_BE_PRESENT = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "MULTISIM_POLICY_",
            value = {MULTISIM_POLICY_NONE, MULTISIM_POLICY_ONE_VALID_SIM_MUST_BE_PRESENT})
    public @interface MultiSimPolicy {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CARRIER_RESTRICTION_DEFAULT_",
            value = {CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED, CARRIER_RESTRICTION_DEFAULT_ALLOWED})
    public @interface CarrierRestrictionDefault {}

    private List<CarrierIdentifier> mAllowedCarriers;
    private List<CarrierIdentifier> mExcludedCarriers;
    @CarrierRestrictionDefault
    private int mCarrierRestrictionDefault;
    @MultiSimPolicy
    private int mMultiSimPolicy;

    private CarrierRestrictionRules() {
        mAllowedCarriers = new ArrayList<CarrierIdentifier>();
        mExcludedCarriers = new ArrayList<CarrierIdentifier>();
        mCarrierRestrictionDefault = CARRIER_RESTRICTION_DEFAULT_NOT_ALLOWED;
        mMultiSimPolicy = MULTISIM_POLICY_NONE;
    }

    private CarrierRestrictionRules(Parcel in) {
        mAllowedCarriers = new ArrayList<CarrierIdentifier>();
        mExcludedCarriers = new ArrayList<CarrierIdentifier>();

        in.readTypedList(mAllowedCarriers, CarrierIdentifier.CREATOR);
        in.readTypedList(mExcludedCarriers, CarrierIdentifier.CREATOR);
        mCarrierRestrictionDefault = in.readInt();
        mMultiSimPolicy = in.readInt();
    }

    /**
     * Creates a new builder for this class
     * @hide
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Indicates if all carriers are allowed
     */
    public boolean isAllCarriersAllowed() {
        return (mAllowedCarriers.isEmpty() && mExcludedCarriers.isEmpty()
                && mCarrierRestrictionDefault == CARRIER_RESTRICTION_DEFAULT_ALLOWED);
    }

    /**
     * Retrieves list of allowed carriers
     *
     * @return the list of allowed carriers
     */
    public @NonNull List<CarrierIdentifier> getAllowedCarriers() {
        return mAllowedCarriers;
    }

    /**
     * Retrieves list of excluded carriers
     *
     * @return the list of excluded carriers
     */
    public @NonNull List<CarrierIdentifier> getExcludedCarriers() {
        return mExcludedCarriers;
    }

    /**
     * Retrieves the default behavior of carrier restrictions
     */
    public @CarrierRestrictionDefault int getDefaultCarrierRestriction() {
        return mCarrierRestrictionDefault;
    }

    /**
     * @return The policy used for multi-SIM devices
     */
    public @MultiSimPolicy int getMultiSimPolicy() {
        return mMultiSimPolicy;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedList(mAllowedCarriers);
        out.writeTypedList(mExcludedCarriers);
        out.writeInt(mCarrierRestrictionDefault);
        out.writeInt(mMultiSimPolicy);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable.Creator}
     */
    public static final Creator<CarrierRestrictionRules> CREATOR =
            new Creator<CarrierRestrictionRules>() {
        @Override
        public CarrierRestrictionRules createFromParcel(Parcel in) {
            return new CarrierRestrictionRules(in);
        }

        @Override
        public CarrierRestrictionRules[] newArray(int size) {
            return new CarrierRestrictionRules[size];
        }
    };

    @Override
    public String toString() {
        return "CarrierRestrictionRules(allowed:" + mAllowedCarriers + ", excluded:"
                + mExcludedCarriers + ", default:" + mCarrierRestrictionDefault
                + ", multisim policy:" + mMultiSimPolicy + ")";
    }

    /**
     * Builder for a {@link CarrierRestrictionRules}.
     */
    public static class Builder {
        private final CarrierRestrictionRules mRules;

        /** {@hide} */
        public Builder() {
            mRules = new CarrierRestrictionRules();
        }

        /** build command */
        public CarrierRestrictionRules build() {
            return mRules;
        }

        /**
         * Indicate that all carriers are allowed.
         */
        public Builder setAllCarriersAllowed() {
            mRules.mAllowedCarriers.clear();
            mRules.mExcludedCarriers.clear();
            mRules.mCarrierRestrictionDefault = CARRIER_RESTRICTION_DEFAULT_ALLOWED;
            return this;
        }

        /**
         * Set list of allowed carriers.
         *
         * @param allowedCarriers list of allowed carriers
         */
        public Builder setAllowedCarriers(List<CarrierIdentifier> allowedCarriers) {
            mRules.mAllowedCarriers = new ArrayList<CarrierIdentifier>(allowedCarriers);
            return this;
        }

        /**
         * Set list of excluded carriers.
         *
         * @param excludedCarriers list of excluded carriers
         */
        public Builder setExcludedCarriers(List<CarrierIdentifier> excludedCarriers) {
            mRules.mExcludedCarriers = new ArrayList<CarrierIdentifier>(excludedCarriers);
            return this;
        }

        /**
         * Set the default behavior of the carrier restrictions
         *
         * @param carrierRestrictionDefault prioritized carrier list
         */
        public Builder setDefaultCarrierRestriction(
                @CarrierRestrictionDefault int carrierRestrictionDefault) {
            mRules.mCarrierRestrictionDefault = carrierRestrictionDefault;
            return this;
        }

        /**
         * Set the policy to be used for multi-SIM devices
         *
         * @param multiSimPolicy multi SIM policy
         */
        public Builder setMultiSimPolicy(@MultiSimPolicy int multiSimPolicy) {
            mRules.mMultiSimPolicy = multiSimPolicy;
            return this;
        }
    }
}