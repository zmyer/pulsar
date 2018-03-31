---
title: Processing guarantees
lead: Apply at-most-once, at-least-once, or effectively-once delivery semantics to Pulsar Functions
new: true
---

Pulsar Functions provides three different messaging semantics that you can apply to any function:

Delivery semantics | Description
:------------------|:-------
**At-most-once** delivery | Each message that is sent to the function will most likely be processed but also may not be (hence the "at most")
**At-least-once** delivery | Each message that is sent to the function could be processed more than once (hence the "at least")
**Effectively-once** delivery | Each message that is sent to the function will have one output associated with it. The function may be invoked more than once, perhaps due to some kind of system failure, but the function will produce one effect for each incoming message.

## Applying processing guarantees to a function

You can set the processing guarantees for a Pulsar Function when you create the Function. This [`pulsar-function create`](../../reference/CliTools#pulsar-admin-functions-create) command, for example, would apply effectively-once guarantees to the Function:

```bash
$ bin/pulsar-functions \
  --processingGuarantees EFFECTIVELY_ONCE \
  # Other function configs
```

The available options are:

* `ATMOST_ONCE`
* `ATLEAST_ONCE`
* `EFFECTIVELY_ONCE`

{% include admonition.html type="info" content="By default, Pulsar Functions provide at-most-once delivery guarantees. So if you create a function without supplying a value for the `--processingGuarantees` flag, then the function will provide at-most-once guarantees." %}

## Updating the processing guarantees of a function

You can change the processing guarantees applied to a function once it's already been created using the [`update`](../../reference/CliTools#pulsar-admin-functions-update) command.